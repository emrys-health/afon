package health.emrys.afon.ops.steps;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import com.azure.storage.blob.models.BlobItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.AfonCloudStack;
import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.cdk.azure.AzureReader;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.ConfigureUsersMain;
import health.emrys.afon.ops.ManagePolarisCatalogsMain;
import health.emrys.afon.ops.RegisterPolarisTableMain;
import health.emrys.afon.ops.spark.SparkRunner;
import health.emrys.afon.snowflake.SnowflakeConnectionFactory;
import org.apache.commons.io.file.PathUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.AZURE_STORAGE_ACCOUNT;
import static health.emrys.afon.config.ConfigUtils.*;
import static health.emrys.afon.ops.ManagePolarisCatalogsMain.*;
import static health.emrys.afon.ops.steps.DurationUtils.formatDuration;
import static java.lang.String.format;
import static java.util.Comparator.comparing;
import static org.slf4j.LoggerFactory.getLogger;

public class StepActions {

    private static final Logger logger = getLogger(StepActions.class);

    /**
     * Deletes the stacks in the cloud selected by the filter, allowing for a single attempt.
     * @param outputStream the stream to which the tofu output is written
     * @throws IOException
     */
    void deleteStacksIn(AfonCloud cloud, StackFilter filter, OutputStream outputStream) throws IOException {
        deleteStacksIn(cloud, filter, 1, outputStream);
    }

    /**
     * Deletes the stacks in the cloud selected by the filter, among the stacks with a stack dir or a tofu state,
     * allowing a given number of retries.  These include the legacy stack, whose dir and tofu state were shared by
     * all the stacks of the cloud, named as the cloud.
     * @param outputStream the stream to which the tofu output is written
     * @throws IOException
     */
    void deleteStacksIn(AfonCloud cloud, StackFilter filter, int maxAttempts, OutputStream outputStream) throws IOException {
        checkNotBrokenSymlink(Path.of(ConfigUtils.afonCkdtf()));
        List<String> stackNames = stackNamesIn(cloud, filter);
        // only a deployed stack, ie with a tofu state, may need keeping: a stack dir without one is just deleted
        checkNoRefusal(filter, deployedStackNames(stackNames, Path.of(ConfigUtils.afonCkdtf())));
        if (stackNames.isEmpty()) {
            logger.info("No stack to delete in cloud {}, as selected by: {}", cloud, filter.description());
        }
        for (String stackName : stackNames) {
            logger.info("Deleting stack {}, as selected by: {}", stackName, filter.description());
            if (stackName.equals(cloud.toString())) {
                deleteStack(Path.of(legacyStackDir(cloud)), Path.of(legacyTfStatePath(cloud)), maxAttempts, outputStream);
            } else {
                StackId stackId = StackId.parse(stackName);
                validateStack(stackId);
                deleteStack(Path.of(ConfigUtils.stackDir(stackId)), Path.of(tfStatePath(stackId)), maxAttempts, outputStream);
                // the kubeconfig of the stack's cluster, which is now gone
                deletePath(Path.of(kubeConfigPath(stackId)));
            }
        }
    }

    /**
     * Checks, before deleting any, that none of the selected deployed stacks must be kept, unless {@code --replace}
     * is given.
     * @param stackNames the names of the selected stacks that are deployed, ie with a tofu state
     * @throws IllegalStateException naming the stacks refused, and why
     */
    void checkNoRefusal(StackFilter filter, List<String> stackNames) {
        List<StackFilter.Refusal> refusals = stackNames.stream().map(filter.refusal()).filter(Objects::nonNull).toList();
        if (refusals.isEmpty()) {
            return;
        }
        String deploying = filter.deploying() == null ? "the stack" : filter.deploying().toString();
        String them = refusals.size() == 1 ? "it" : "them";
        List<String> deleteCommands = refusals.stream().map(StackFilter.Refusal::deleteCommand)
                                              .filter(Objects::nonNull).distinct().toList();
        StringBuilder message = new StringBuilder(format("Cannot deploy %s, as %s.%n", deploying,
            refusals.stream().map(StackFilter.Refusal::reason).collect(Collectors.joining(";\nand "))));
        if (!deleteCommands.isEmpty()) {
            message.append(format("To deploy %s, delete %s first:%n", deploying, them));
            deleteCommands.forEach(command -> message.append(format("    %s%n", command)));
            message.append(format("or rerun with --replace to delete %s as part of this run.", them));
        } else {
            message.append(format("To deploy %s, rerun with --replace to delete %s as part of this run.", deploying, them));
        }
        throw new IllegalStateException(message.toString());
    }

    /**
     * Returns the names of the stacks in the cloud selected by the filter, see {@link #stackNamesIn(AfonCloud)}.
     */
    List<String> stackNamesIn(AfonCloud cloud, StackFilter filter) throws IOException {
        return stackNamesIn(cloud).stream().filter(filter.selects()).toList();
    }

    /**
     * Returns the names of the stacks in the cloud, ie of the stack dirs and tofu states found in the cdktf dir,
     * eg {@code aws-keycloak} for {@code stacks/aws-keycloak} or {@code terraform.aws-keycloak.tfstate},
     * and {@code aws} for the legacy {@code stacks/aws} or {@code terraform.aws.tfstate}.
     */
    List<String> stackNamesIn(AfonCloud cloud) throws IOException {
        return stackNamesIn(cloud, Path.of(ConfigUtils.afonCkdtf()));
    }

    /**
     * @return the names of the stacks that are deployed, ie with a tofu state in the cdktf dir, eg
     * {@code terraform.aws-keycloak.tfstate} for {@code aws-keycloak}, or {@code terraform.aws.tfstate} for the legacy
     * {@code aws}, rather than just a stack dir, eg left from synthesising a stack without deploying it
     */
    List<String> deployedStackNames(List<String> stackNames, Path cdktfDir) {
        return stackNames.stream()
                         .filter(name -> exists(cdktfDir.resolve(TF_STATE_PREFIX + name + TF_STATE_SUFFIX)))
                         .toList();
    }

    static List<String> stackNamesIn(AfonCloud cloud, Path cdktfDir) throws IOException {
        Set<String> stackNames = new TreeSet<>();
        Path stacksDir = cdktfDir.resolve(STACKS_DIR_NAME);
        if (Files.isDirectory(stacksDir)) {
            try (var dirs = Files.list(stacksDir)) {
                dirs.filter(Files::isDirectory)
                    .map(dir -> dir.getFileName().toString())
                    .filter(name -> isStackNameIn(cloud, name))
                    .forEach(stackNames::add);
            }
        }
        if (Files.isDirectory(cdktfDir)) {
            try (var files = Files.list(cdktfDir)) {
                files.map(file -> file.getFileName().toString())
                     .filter(name -> name.startsWith(TF_STATE_PREFIX) && name.endsWith(TF_STATE_SUFFIX))
                     .map(name -> name.substring(TF_STATE_PREFIX.length(), name.length() - TF_STATE_SUFFIX.length()))
                     .filter(name -> isStackNameIn(cloud, name))
                     .forEach(stackNames::add);
            }
        }
        return new ArrayList<>(stackNames);
    }

    private static final String STACKS_DIR_NAME = "stacks";
    private static final String TF_STATE_PREFIX = "terraform.";
    private static final String TF_STATE_SUFFIX = ".tfstate";

    private static boolean isStackNameIn(AfonCloud cloud, String name) {
        return name.equals(cloud.toString()) || name.startsWith(cloud + "-");
    }

    /**
     * Validates the plan of a deployed stack from the current config, so that {@code tofu destroy} doesn't
     * run on the plan left by the last deploy or validation, which can be stale, eg after a change of config.
     * It also fails early, before anything is destroyed, if the stack no
     * longer validates, eg because of a missing property.  Does nothing if the stack is not deployed, ie has no
     * tofu state, or has no stack class, as the legacy stacks.
     * @throws IllegalStateException if the stack fails to synthesise
     */
    void validateStack(StackId stackId) {
        validateStack(stackId, Path.of(tfStatePath(stackId)));
    }

    void validateStack(StackId stackId, Path tfState) {
        if (!exists(tfState)) {
            return;
        }
        CloudStackValidator validator = new CloudStackValidator();
        Class<? extends AfonCloudStack> stack = validator.resolve(stackId.toString());
        if (stack == null) {
            logger.info("No stack class for stack {}, so not validating it before deleting it", stackId);
            return;
        }
        logger.info("Validating stack {} again before deleting it, so that its plan is current", stackId);
        try {
            validator.validate(stack);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(String.format(
                "Cannot delete stack %s, as it failed to validate against the current config: %s%n"
                + "Fix the config, as validated with 'afon-cli.sh validate-stack %s', and run again.",
                stackId, cause.getMessage(), stackId), cause);
        }
    }

    /**
     * Deletes the stack by running {@code tofu destroy} in its dir, if it has a tofu state, then deleting
     * its dir and tofu state.
     * @throws IllegalStateException if the stack has a tofu state but no dir, as tofu cannot destroy it
     */
    void deleteStack(Path stackDir, Path tfState, int maxAttempts, OutputStream outputStream) throws IOException {
        if (!exists(tfState)) {
            logger.info("No tofu state {}, so nothing deployed from stack dir {}", tfState, stackDir);
            deletePath(stackDir);
            return;
        }
        if (!exists(stackDir)) {
            throw new IllegalStateException(String.format("""
                Found tofu state %s but no stack dir %s, so tofu cannot destroy the stack.
                Recreate the stack dir by validating the stack, ie with the same config:
                    afon-cli.sh validate-stack <stack-id>
                and run again.
                If you're sure nothing of the stack is still running, delete the tofu state %s.
                """, tfState, stackDir, tfState));
        }
        try {
            tofuDestroy(stackDir, maxAttempts, outputStream);
            deletePath(stackDir);
            deletePath(tfState);
        } catch (ManagedProcessException e) {
            String message = String.format("""
                Failed to run, after %s attempt(s):
                    tofu destroy
                Your environment may be in an unstable state.
                It may be that something like a Helm chart is blocking the destroy.
                If so, remove it with something like:
                    tofu state rm 'helm_release.prometheusRelease'
                and run again:
                    tofu destroy
                
                If you're sure nothing in the environment is still running, delete the tofu dir %s.
                """, maxAttempts, stackDir);
            logger.error(message, e);
            throw new RuntimeException(e);
        }
    }

    boolean exists(Path path) {
        return Files.exists(path);
    }

    @NonNull Path stackDir(StackId stackId) throws IOException {
        checkNotBrokenSymlink(Path.of(ConfigUtils.afonCkdtf()));
        Path stackDir = Path.of(ConfigUtils.stackDir(stackId));
        logger.info("Using stack dir {}", stackDir);
        if (!Files.exists(stackDir)){
            Files.createDirectories(stackDir);
            logger.info("Created new stack dir {}, as it did not exist.", stackDir);
        }
        return stackDir;
    }

    void checkNotBrokenSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path) && !Files.exists(path)) {
            throw new IOException(String.format(
                "%s is a symlink pointing to %s, which does not exist. Fix or recreate the symlink target.",
                path, Files.readSymbolicLink(path)));
        }
    }

    private void tofuDestroy(Path tofuDir, int maxAttempts, OutputStream outputStream) throws ManagedProcessException {
        logger.info("Executing tofu destroy in dir {}", tofuDir);
        run("tofu", tofuDir, outputStream, "init");
        String[] args = List.of("destroy", "--auto-approve").toArray(new String[0]);
        tofuWithRetry(tofuDir, outputStream, maxAttempts, args);
    }

    void deletePath(Path path) {
        if (!exists(path)) {
            return;
        }
        logger.info("Deleting path {}", path);
        try {
            PathUtils.delete(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void awsDescribeCertificate(String certArn, String region, OutputStream out) throws ManagedProcessException {
        run("aws", null, out, "acm", "describe-certificate", "--certificate-arn", certArn,
                "--region", region, "--query", "Certificate.Status", "--output", "text");
    }

    /**
     * Starts the stack, allowing for a single attempt.
     * @param outputStream the stream to which the tofu output is written
     * @throws Exception
     */
    void startStack(Class<?> stackClass, OutputStream outputStream) throws Exception {
        startStack(stackClass, 1, outputStream);
    }

    /**
     * Starts the stack, attempting for a given number of retries, in the stack dir of its {@link StackId},
     * named from the stack class, eg {@code aws-keycloak} for {@code AwsKeycloakStack}.
     * @param outputStream the stream to which the tofu output is written
     * @throws Exception
     */
    void startStack(Class<?> stackClass, int maxAttempts, OutputStream outputStream) throws Exception {
        executeMainOf(stackClass, new String[]{});
        startManagedStack(StackId.of(stackClass), maxAttempts, outputStream);
    }

    private void startManagedStack(StackId stackId, int maxAttempts, OutputStream outputStream) throws IOException {
        // The kubernetes/helm providers are configured from the managed cluster's endpoint and
        // credentials (EKS endpoint+token on AWS, the AKS kubeconfig on Azure). With the v3
        // (plugin-framework) providers, those must be known at plan time, otherwise apply fails
        // with "Provider produced inconsistent final plan" (the provider can't be configured
        // until the cluster exists). So we bootstrap ONLY the cluster in a targeted first phase;
        // the second, full apply then plans the k8s/helm resources against a cluster that
        // already exists.
        //
        // We deliberately target only the control plane, not the worker nodes. On AWS the node
        // group has no CNI of its own and only becomes Ready once Cilium (a Helm release) is
        // installed, so it must stay in phase two alongside Cilium; the EKS cluster resource
        // does not include the node group. On Azure the default node pool is declared inline on
        // the cluster resource (and uses AKS-managed Cilium), so targeting the cluster brings up
        // the nodes too. On GCP the default node pool is likewise part of the
        // google_container_cluster resource and uses GKE's built-in CNI, so the targeted apply
        // yields a Ready cluster whose endpoint and CA cert are then known for phase two.
        // Stacks without a managed cluster yield no targets and skip straight to the full apply.
        Path stackDir = stackDir(stackId);
        tofuApply(stackDir, clusterBootstrapTargets(stackDir), maxAttempts, outputStream);
    }

    private void tofuApply(Path tofuDir, List<String> bootstrapTargets, int maxAttempts, OutputStream outputStream) throws IOException {
        ensureKubeConfigExists();
        if (run("tofu", tofuDir, outputStream, "init") != 0) {
            throw new ManagedProcessException(format("tofu init failed in %s", tofuDir));
        }
        if (!bootstrapTargets.isEmpty()) {
            List<String> phaseOne = new ArrayList<>(List.of("apply", "--auto-approve", "-parallelism=5"));
            phaseOne.addAll(bootstrapTargets);
            logger.info("Bootstrapping targets before the full apply: {}", phaseOne);
            tofuWithRetry(tofuDir, outputStream, maxAttempts, phaseOne.toArray(new String[0]));
        }
        tofuWithRetry(tofuDir, outputStream, maxAttempts, "apply", "--auto-approve", "-parallelism=5");
    }

    /**
     * Retries a {@code tofu} invocation on failure.
     * Retrying is safe because {@code tofu} re-plans against current state each time.
     */
    private void tofuWithRetry(Path tofuDir, OutputStream outputStream, int maxAttempts, String... args) throws ManagedProcessException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int exitCode = run("tofu", tofuDir, outputStream, args);
                if (exitCode == 0) {
                    return;
                }
                throwOrRetry(args, attempt, maxAttempts, new ManagedProcessException(String.format(
                    "tofu %s exited with code %d", String.join(" ", args), exitCode)));
            } catch (ManagedProcessException e) {
                // ManagedProcess.waitForExit() throws on a non-zero exit code rather than returning it,
                // so a failed `tofu` invocation surfaces here, not via the exitCode check above.
                throwOrRetry(args, attempt, maxAttempts, e);
            }
        }
    }

    /**
     * Throws failure exception if the attempts have reached its max number allowed,
     * else waits for 30s and carries on to the following attempt.
     * @throws ManagedProcessException
     */
    private void throwOrRetry(String[] args, int attempt, int maxAttempts, ManagedProcessException failure) throws ManagedProcessException {
        if (attempt == maxAttempts) {
            throw failure;
        }
        final Duration duration = Duration.ofSeconds(30);
        logger.warn("tofu {} failed on attempt {}/{}, retrying in {}s",
                 String.join(" ", args), attempt, maxAttempts, duration.toSeconds(), failure);
        sleepUninterruptibly(duration);
    }

    private void sleepUninterruptibly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * The kubernetes/helm providers' {@code config_path} must exist before tofu can even plan the
     * second, full apply above — but the resource that writes real content there only runs inside
     * that same apply, once the cluster it depends on exists; {@code -target} on the cluster alone
     * does not pull in that resource, since it is a dependent, not a dependency. On a workstation
     * this is invisible because {@code ~/.kube/config} already exists from prior manual use —
     * stale content is fine, since it gets overwritten once the updater resource runs.
     * A machine that has never run either (a fresh CI runner) has no such file, and the
     * providers fail outright with "no such file or directory". An empty file is enough.
     */
    private void ensureKubeConfigExists() throws IOException {
        Path kubeConfig = Path.of(System.getProperty("user.home"), ".kube", "config");
        if (!Files.exists(kubeConfig)) {
            Files.createDirectories(kubeConfig.getParent());
            Files.createFile(kubeConfig);
        }
    }

    private void executeMainOf(Class mainClass, String[] args) throws Exception {
        executeMainOf(mainClass, args, new Properties());
    }

    protected void executeMainOf(Class mainClass, String[] args, Properties overrides) throws Exception {
        logger.info("Running {} with arguments {} and overrides {}",
                 mainClass.getName(),
                 String.join(" ", args),
                 overrides);

        Properties original = setSystemProperties(overrides);
        try {
            Method main = mainClass.getDeclaredMethod("main", String[].class);
            String[] mainArgs = Arrays
                .stream(args)
                .filter(s -> s != null && !s.isEmpty())
                .toArray(String[]::new);
            main.invoke(null, (Object) mainArgs);
        } finally {
            restoreSystemProperties(overrides, original);
        }
    }

    private @NonNull Properties setSystemProperties(Properties properties) {
        Properties original = new Properties();
        properties.keySet().forEach(mapKey -> {
            String key        = mapKey.toString();
            String property   = System.getProperty(key);
            if (property != null) {
                original.put(key, property);
            }
            String value = properties.getProperty(key);
            logger.info("Setting system property {} = {}", key, value);
            System.setProperty(key, value);
        });
        return original;
    }

    private void restoreSystemProperties(Properties properties, Properties original) {
        properties.keySet().forEach(mapKey -> {
            String key   = mapKey.toString();
            String value = original.getProperty(key);
            logger.info("Restoring system property {} = {}", key, value);
            if (value != null) {
                System.setProperty(key, value);
            }  else {
                System.clearProperty(key);
            }
        });
    }

    /**
     * Terraform resource types for the managed Kubernetes control planes whose endpoint and
     * credentials the kubernetes/helm providers depend on at plan time. We bootstrap these in a
     * targeted first apply phase. GCP is included even though it authenticates via an exec plugin:
     * that plugin only supplies the auth token on demand, but the provider's {@code host} and
     * {@code cluster_ca_certificate} are still read from the {@code google_container_cluster}
     * resource, so the cluster must exist at plan time to avoid an inconsistent final plan.
     */
    private static final List<String> MANAGED_CLUSTER_RESOURCE_TYPES =
        List.of("aws_eks_cluster", "azurerm_kubernetes_cluster", "google_container_cluster");

    /**
     * Reads config file {@code cdk.tf.json} in the given stack dir and returns {@code -target=...}
     * arguments for any managed Kubernetes cluster it declares, so the cluster can be created in
     * a first apply phase. This makes the cluster endpoint/credentials known at plan time for the
     * second, full apply, which is what the v3 kubernetes/helm providers require. Returns an
     * empty list (and logs nothing fatal) when the file is absent or declares no such cluster.
     */
    private List<String> clusterBootstrapTargets(Path stackDir) {
        List<String> targets = new ArrayList<>();
        Path config = stackDir.resolve("cdk.tf.json");
        if (!config.toFile().exists()) {
            return targets;
        }
        try {
            JsonNode resources = new ObjectMapper().readTree(config.toFile()).path("resource");
            for (String type : MANAGED_CLUSTER_RESOURCE_TYPES) {
                resources.path(type).fieldNames()
                         .forEachRemaining(name -> targets.add("-target=" + type + "." + name));
            }
        } catch (IOException e) {
            logger.warn("Could not read config file {} to compute cluster bootstrap targets; "
                     + "proceeding with a single apply", config, e);
        }
        return targets;
    }

    public int run(String command, Path path, String... commands) throws ManagedProcessException {
        return run(command, path, System.out, commands);
    }

    public int run(String command, Path path, OutputStream stdOut, String... commands) throws ManagedProcessException {
        ManagedProcessBuilder pb = new ManagedProcessBuilder(command);
        for (String cmd : commands) {
            pb.addArgument(cmd);
        }
        if (path != null) {
            pb.setWorkingDirectory(path.toFile());
        }
        pb.setDestroyOnShutdown(true)
          .addStdOut(stdOut)
          .addStdErr(System.err)
          .setConsoleBufferMaxLines(7000);
        ManagedProcess p = pb.build();
        p.start();
        p.isAlive();
        p.waitForExit();
        logger.info("Command {} finished with value {}", command, p.exitValue());
        return p.exitValue();
    }

    public void createAllCatalogsFor(AfonCloud cloud) {
        try {
            executeMainOf(ManagePolarisCatalogsMain.class,
                    new String[]{RECREATE, cloud.toString()});
        } catch (Exception e) {
            logger.error(
                    """
                    Failed to delete and recreate all catalogs in Polaris for cloud `{}`.
                    It may be because the certificates are not yet ready. 
                    The hint is an error message such as:
                    
                    PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
                    unable to find valid certification path to requested target
                    
                    If this is the case, try re-running this step.                         
                    """, cloud, e);
        }
    }

    public void createCatalogFor(AfonCloud cloud, AfonCatalog catalog) throws Exception {
        executeMainOf(ManagePolarisCatalogsMain.class,
                new String[]{CREATE, cloud.toString(), catalog.toString()});
    }

    public void configureOidcUsersFor(AfonCloud cloud) throws Exception {
        executeMainOf(ConfigureUsersMain.class,
                new String[]{cloud.toString()});
    }

    public void configurePolarisUsersFor(AfonCloud cloud, AfonCatalog catalog) throws Exception {
        executeMainOf(ConfigureUsersMain.class,
                new String[]{cloud.toString(), catalog.toString()});
    }

    public void deleteCatalogFor(AfonCloud cloud, AfonCatalog catalog) throws Exception {
        executeMainOf(ManagePolarisCatalogsMain.class,
                new String[]{DELETE, cloud.toString(), catalog.toString()});
    }

    public void deleteNamespaceFor(AfonCloud cloud, AfonCatalog catalog, String namespace) throws Exception {
        executeMainOf(
                ManagePolarisCatalogsMain.class,
                new String[]{DELETE, cloud.toString(), catalog.toString(), namespace});
    }

    public void deleteTableFor(AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName) throws Exception {
        executeMainOf(
                ManagePolarisCatalogsMain.class,
                new String[]{DELETE, cloud.toString(), catalog.toString(), namespace, tableName});
    }

    public void registerTable(AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName, String metadata) throws Exception {
        executeMainOf(RegisterPolarisTableMain.class,
                new String[]{cloud.toString(), catalog.toString(), namespace, tableName, metadata});
    }

    public void runSpark(Class<? extends SparkRunner> sparkRunner, AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName) throws Exception {
        executeMainOf(sparkRunner,
                new String[]{cloud.toString(), catalog.toString(), namespace, tableName});
    }

    @NonNull String azureFirstFile(AfonPropertyProvider properties, String glob, boolean descendingOrder) {
        logger.info("Querying Azure for {}", glob);
        AzureReader reader = new AzureReader(properties);
        List<BlobItem> list = new ArrayList<>(reader.list(glob));
        Comparator<BlobItem> comparing = comparing((BlobItem o) -> o.getProperties().getCreationTime());
        if (descendingOrder) {
            comparing = comparing.reversed();
        }
        BlobItem latest = list
                .stream()
                .sorted(comparing)
                .findFirst()
                .get();
        logger.info("Latest blob was created at {}", latest.getProperties().getCreationTime());
        return format(
                "abfss://%s@%s.dfs.core.windows.net/%s",
                properties.getCatalogProperty("AZURE_BUCKET_NAME", "azure"),
                properties.getValue(AZURE_STORAGE_ACCOUNT),
                latest.getName());
    }


    public void runSnowflakeSql(AfonPropertyProvider properties, String[] statements) throws NoSuchAlgorithmException, InvalidKeySpecException, SQLException {
        SnowflakeConnectionFactory factory = new SnowflakeConnectionFactory(properties);
        try (Connection conn = factory.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                logger.info("Running sql:\n{}", sql);
                ResultSet rs = stmt.executeQuery(sql);
                while (rs.next()) {
                    logger.info(rs.getString(1));
                }
            }
            conn.commit();
        }
    }

    /**
     * Checks that the URL answers a GET with 200, eg that a server another stack deploys is up.
     * @param failure what to say if it doesn't, eg what to deploy first
     * @throws IllegalStateException if it doesn't answer, or not with 200
     */
    void checkAnswers(String url, String failure) throws InterruptedException {
        logger.info("Checking {} answers", url);
        String problem;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        try {
            HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) {
                logger.info("{} answers", url);
                return;
            }
            problem = "answered " + response.statusCode();
        } catch (IOException e) {
            problem = "cannot be reached: " + e;
        }
        throw new IllegalStateException(format("%s %s. %s", url, problem, failure));
    }

    public Step waitOnDNS(String domain, long timeout) {
        return new Step(
                stepNameWaitOn(domain),
                "wait on DNS for domain " + domain,
                () -> {
                    boolean resolved      = false;
                    boolean stopResolving = false;
                    long    start         = System.currentTimeMillis();
                    logger.info("Checking DNS for {}", domain);
                    while (!stopResolving) {
                        long elapsed = System.currentTimeMillis() - start;
                        logger.info("Waiting on DNS for domain {} (until timeout of {}): time elapsed {}", domain, formatDuration(timeout), formatDuration(elapsed));
                        if (!(elapsed < timeout)) break;
                        try {
                            InetAddress[] addresses = InetAddress.getAllByName(domain);
                            resolved = addresses != null && addresses.length > 0;
                            stopResolving = resolved;
                            if (!stopResolving) {
                                stopResolving = isWaitingInterrupted(timeout);
                            }
                        } catch (UnknownHostException e) {
                            stopResolving = isWaitingInterrupted(timeout);
                        }
                    }
                    if (!resolved) {
                        logger.warn("Timed out waiting on DNS for domain {} after {}", domain, formatDuration(timeout));
                    }
                });
    }


    @NonNull String stepNameWaitOn(String domain) {
        return "wait_on_" + (domain.isBlank() ? "domain" : domain.substring(0, domain.indexOf('.')));
    }

    private boolean isWaitingInterrupted(long timeout) {
        long pauseMs = 15_000;
        logger.debug("Waiting {}s until timeout of {}s", pauseMs / 1000, timeout / 1000);
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            return true;
        }
        return false;
    }

}
