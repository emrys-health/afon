package health.emrys.afon.ops.steps;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import com.azure.storage.blob.models.BlobItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.azure.AzureReader;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.ops.ManagePolarisCatalogsMain;
import health.emrys.afon.ops.RegisterPolarisTableMain;
import health.emrys.afon.snowflake.SnowflakeConnectionFactory;
import org.apache.commons.io.file.PathUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static health.emrys.afon.config.AfonProperty.AZURE_BUCKET_NAME;
import static health.emrys.afon.config.AfonProperty.AZURE_STORAGE_ACCOUNT;
import static health.emrys.afon.config.ConfigUtils.tfStatePath;
import static health.emrys.afon.ops.steps.DurationUtils.formatDuration;
import static java.lang.String.format;
import static java.util.Comparator.comparing;
import static org.slf4j.LoggerFactory.getLogger;

public class StepActions {

    private static final Logger logger = getLogger(StepActions.class);

    @NonNull Path deleteStack(AfonCloud cloud) throws IOException {
        Path stackDir = stackDir(cloud);
        tofuDestroy(stackDir);
        deletePath(stackDir);
        deleteTfStateFiles(cloud);
        return stackDir;
    }

    private @NonNull Path stackDir(AfonCloud cloud) throws IOException {
        Path stackDir = Path.of(ConfigUtils.stackDir(cloud));
        logger.info("Using stack dir {}", stackDir);
        if (!Files.exists(stackDir)){
            stackDir.toFile().mkdirs();
            logger.info("Created new stack dir {}, as it did not exist.", stackDir);
        }
        return stackDir;
    }

    private void tofuDestroy(Path tofuDir) {
        try {
            logger.info("Stopping {} if it's running already...", tofuDir);
            run("tofu", tofuDir, "init");
            if (run("tofu", tofuDir, "destroy", "--auto-approve") != 0) {
                String msg = String.format("""
                Failed to destroy %s. Your environment may be in an unstable state.
                It may be that something irrelevant (like a Helm chart) is blocking the destroy.
                If so, remove it with something like:
                    tofu state rm 'helm_release.prometheusRelease'
                and tofu destroy again.
                
                If you're sure nothing in the environment is still running, delete directory %s
                """, tofuDir, tofuDir);
                logger.error(msg);
                throw new RuntimeException(msg);
            }
        } catch (Exception e) {
            logger.error("Failed to call tofu destroy in " + tofuDir, e);
        }
    }

    void deleteTfStateFiles(AfonCloud cloud) throws IOException {
        deletePath(Path.of(tfStatePath(cloud)));
    }

    void deletePath(Path path) {
        logger.info("Deleting path {}", path);
        try {
            PathUtils.delete(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void startStack(Class stackClass, AfonCloud cloud) throws Exception {
        executeMainOf(stackClass, new String[]{});
        startManagedStack(cloud);
    }

    private void startManagedStack(AfonCloud cloud) throws ManagedProcessException, IOException {
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
        Path stackDir = stackDir(cloud);
        List<String> bootstrapTargets = clusterBootstrapTargets(stackDir);
        tofuApply(stackDir, bootstrapTargets);
    }

    private void tofuApply(Path tofuDir, List<String> bootstrapTargets) throws ManagedProcessException, IOException {
        ensureKubeConfigExists();
        run("tofu", tofuDir, "init");
        if (!bootstrapTargets.isEmpty()) {
            List<String> phaseOne = new ArrayList<>(List.of("apply", "--auto-approve", "-parallelism=5"));
            phaseOne.addAll(bootstrapTargets);
            logger.info("Bootstrapping EKS before the full apply: {}", phaseOne);
            run("tofu", tofuDir, phaseOne.toArray(new String[0]));
        }
        run("tofu", tofuDir, OutputStream.nullOutputStream(), "apply", "--auto-approve", "-parallelism=5");
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

    /**
     * @param cloud
     * @param catalog
     * @param namespace
     * @param tableName
     * @param metadata
     * @throws Exception
     */
    public void registerTable(AfonCloud cloud,
                              AfonCatalog catalog,
                              String namespace,
                              String tableName,
                              String metadata) throws Exception {
        executeMainOf(RegisterPolarisTableMain.class,
                new String[]{cloud.toString(),
                        catalog.toString(),
                        namespace,
                        tableName,
                        metadata});
    }

    private void executeMainOf(Class mainClass, String[] args) throws Exception {
        executeMainOf(mainClass, args, new Properties());
    }

    void executeMainOf(Class mainClass, String[] args, Properties overrides) throws Exception {
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
                properties.getValue(AZURE_BUCKET_NAME),
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
    
    void createSingleCatalogsFor(AfonCloud cloud,
                                 AfonCatalog catalog) throws Exception {
        createSingleCatalogsFor(cloud, catalog, new Properties());
    }

    void createSingleCatalogsFor(AfonCloud cloud,
                                 AfonCatalog catalog,
                                 Properties properties) throws Exception {
        executeMainOf(ManagePolarisCatalogsMain.class,
                      new String[] {ManagePolarisCatalogsMain.CREATE, cloud.toString(), catalog.name()},
                      properties);
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
