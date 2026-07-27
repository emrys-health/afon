package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.aws.AwsWholeStack;
import health.emrys.afon.cdk.aws.PrometheusClusterDeploy;
import health.emrys.afon.cdk.aws.SparkClusterDeployment;
import health.emrys.afon.cdk.azure.AzureKubernetesPolaris;
import health.emrys.afon.cdk.azure.AzureWholeStack;
import health.emrys.afon.cdk.gcp.GcpKubernetesPolaris;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.core.configuration.LocalPolarisRegisterMain;
import health.emrys.afon.infra.EndpointPolarisConfiguration;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperties.SupportedCloudsEnum.AWS;
import static health.emrys.afon.polaris.PolarisConstants.ACCEPTANCE_TEST_NAMESPACE;
import static org.slf4j.LoggerFactory.getLogger;

public class StepActions {

    private static final Logger log = getLogger(StepActions.class);
    public static final String TERRAFORM_STATE_FILE_PATTERN = "terraform.";

    public Step waitFor(String domain) {
        return new Step(
            stepNameWaitingFor(domain),
            "wait for internet DNS servers to recognise " + domain,
            () -> {
                            boolean stopResolving = false;
                            long    start         = System.currentTimeMillis();
                            int     timeout       = 1_200_000;
                            log.info("Checking DNS for {}", domain);
                            while (!stopResolving && System.currentTimeMillis() - start < timeout) {
                                try {
                                    InetAddress[] addresses = InetAddress.getAllByName(domain);
                                    stopResolving = addresses != null && addresses.length > 0;
                                    if (!stopResolving) {
                                        stopResolving = isWaitingInterrupted(timeout);
                                    }
                                } catch (UnknownHostException e) {
                                    stopResolving = isWaitingInterrupted(timeout);
                                }
                            }
                        });
    }

    static @NonNull String stepNameWaitingFor(String domain) {
        return "waitFor_" + domain.substring(0, domain.indexOf('.'));
    }

    private boolean isWaitingInterrupted(int timeout) {
        int pauseMs = 15_000;
        log.info("Waiting {}s. Maximum = {}s", pauseMs / 1000, timeout / 1000);
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            return true;
        }
        return false;
    }

    public @NonNull Path deleteStack(SupportedCloudsEnum id) throws IOException {
        Path toDelete = cdktfPath(id);
        if (toDelete.toFile().exists()) {
            attemptGracefulDestroy(toDelete);
            log.info("Deleting {}", toDelete);
            delete(toDelete);
        } else {
            log.info("Directory {} does not exist so starting afresh", toDelete);
        }
        deleteTerraformStateFiles(id);
        return toDelete;
    }

    private void attemptGracefulDestroy(Path path) {
        try {
            log.info("Stopping {} if it's running already...", path);
            run("tofu", path, "init");
            if (run("tofu", path, "destroy", "--auto-approve") != 0) {
                String msg = String.format("""
                Failed to destroy %s. Your environment may be in an unstable state.
                It may be that something irrelevant (like a Helm chart) is blocking the destroy.
                If so, remove it with something like:
                    tofu state rm 'helm_release.prometheusRelease'
                and tofu destroy again.
                
                If you're sure nothing in the environment is still running, delete directory %s
                """, path, path);
                log.error(msg);
                throw new RuntimeException(msg);
            }
        } catch (Exception e) {
            log.error("Failed to call tofu destroy in " + path, e);
        }
    }

    public @NonNull Path cdktfPath(SupportedCloudsEnum id) {
        return Path.of("cdktf.out", "stacks", id.toString());
    }

    public void startGcpPolaris() throws Exception {
        executeMainOf(GcpKubernetesPolaris.class, new String[]{});
        startManagedKubernetesStack(GcpKubernetesPolaris.ID);
    }

    public void startFullStackAzure() throws Exception {
        executeMainOf(AzureWholeStack.class, new String[]{});
        startManagedKubernetesStack(AzureKubernetesPolaris.ID);
    }

    public void startAzurePolaris() throws Exception {
        executeMainOf(AzureKubernetesPolaris.class, new String[]{});
        startManagedKubernetesStack(AzureKubernetesPolaris.ID);
    }

    public void startFullStackInAws() throws Exception {
        executeMainOf(PrometheusClusterDeploy.class, new String[]{});
        startManagedKubernetesStack(AWS);
    }

    public void startSparkClusterInAws() throws Exception {
        executeMainOf(SparkClusterDeployment.class, new String[]{});
        startManagedKubernetesStack(AWS);
    }

    public void startAwsPolaris() throws Exception {
        executeMainOf(AwsWholeStack.class, new String[]{});
        startManagedKubernetesStack(AWS);
    }

    private void startManagedKubernetesStack(SupportedCloudsEnum id) throws ManagedProcessException {
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
        Path         jsonDirectory    = cdktfPath(id);
        List<String> bootstrapTargets = clusterBootstrapTargets(jsonDirectory);
        startCloud(jsonDirectory, bootstrapTargets);
    }

    // AWS acceptancetests AwsAccessSpec251117511401 s3://emrys-afon-bucket/acceptancetests/AwsAccessSpec251117511401/metadata/00000-685ec54b-684f-48cb-bb58-6ae6aacb92e6.metadata.json emryspolaris.click

    /**
     * @param storageCloud eg, GCP
     * @param metadataLocation eg gs://emrys_afon_core/acceptancetests/GcpAccessSpec260430301114/metadata/00000-ce6be45b-1257-4325-b437-20900bbcf2a3.metadata.json
     * @param polarisDomainNAme eg emryspolaris.click
     * @param tableName eg AwsAccessSpec
     * @throws Exception
     */
    public void registerTable(SupportedCloudsEnum storageCloud,
                              String metadataLocation,
                              String polarisDomainNAme,
                              String tableName) throws Exception {
        executeMainOf(LocalPolarisRegisterMain.class,
                      new String[]{storageCloud.name(),
                                   ACCEPTANCE_TEST_NAMESPACE,
                                   tableName,
                                   metadataLocation,
                                   polarisDomainNAme});
    }

    private void executeMainOf(Class clazz, String[] args) throws Exception {
        executeMainOf(clazz, args, new Properties());
    }

    public void executeMainOf(Class clazz, String[] args, Properties overrides) throws Exception {
        log.info("Running {} with arguments {} and overrides {}",
                 clazz.getName(),
                 String.join(" ", args),
                 overrides);

        Properties original = saveAndSetSystem(overrides);
        try {
            Method main = clazz.getDeclaredMethod("main", String[].class);
            String[] mainArgs = Arrays
                .stream(args)
                .filter(s -> s != null && !s.isEmpty())
                .toArray(String[]::new);
            main.invoke(null, (Object) mainArgs);
        } finally {
            restoreSystemProperties(overrides, original);
        }
    }

    public void startCloud(Path jsonDirectory, List<String> bootstrapTargets) throws ManagedProcessException {
        run("tofu", jsonDirectory, "init");
        if (!bootstrapTargets.isEmpty()) {
            List<String> phaseOne = new ArrayList<>(List.of("apply", "--auto-approve", "-parallelism=5"));
            phaseOne.addAll(bootstrapTargets);
            log.info("Bootstrapping EKS before the full apply: {}", phaseOne);
            run("tofu", jsonDirectory, phaseOne.toArray(new String[0]));
        }
        OutputStream suppressedStdOut = OutputStream.nullOutputStream();
        run("tofu", jsonDirectory, suppressedStdOut, "apply", "--auto-approve", "-parallelism=5");
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
     * Reads {@code cdk.tf.json} in the given stack directory and returns {@code -target=...}
     * arguments for any managed Kubernetes cluster it declares, so the cluster can be created in
     * a first apply phase. This makes the cluster endpoint/credentials known at plan time for the
     * second, full apply, which is what the v3 kubernetes/helm providers require. Returns an
     * empty list (and logs nothing fatal) when the file is absent or declares no such cluster.
     */
    private List<String> clusterBootstrapTargets(Path jsonDirectory) {
        List<String> targets = new ArrayList<>();
        Path json = jsonDirectory.resolve("cdk.tf.json");
        if (!json.toFile().exists()) {
            return targets;
        }
        try {
            JsonNode resources = new ObjectMapper().readTree(json.toFile()).path("resource");
            for (String type : MANAGED_CLUSTER_RESOURCE_TYPES) {
                resources.path(type).fieldNames()
                         .forEachRemaining(name -> targets.add("-target=" + type + "." + name));
            }
        } catch (IOException e) {
            log.warn("Could not read {} to compute cluster bootstrap targets; "
                     + "proceeding with a single apply", json, e);
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
        log.info("Command {} finished with value {}", command, p.exitValue());
        return p.exitValue();
    }

    public void deleteTerraformStateFiles(SupportedCloudsEnum id) throws IOException {
        deleteTerraformIn(".", id);
        deleteTerraformIn("..", id);
    }

    public void runSnowflakeSql(String[] statements) throws NoSuchAlgorithmException, InvalidKeySpecException, SQLException {
        SnowflakeConnectionFactory factory = new SnowflakeConnectionFactory();
        try (Connection conn = factory.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                log.info("Running sql:\n{}", sql);
                ResultSet rs = stmt.executeQuery(sql);
                while (rs.next()) {
                    log.info(rs.getString(1));
                }
            }
            conn.commit();
        }
    }
    
    private void deleteTerraformIn(String directory,
                                   SupportedCloudsEnum id) throws IOException {
        Files.list(Path.of(directory))
             .filter(x -> {
                 String fileName = x.getFileName().toString();
                 return fileName.startsWith(TERRAFORM_STATE_FILE_PATTERN + id)
                     && fileName.endsWith(".tfstate");
             })
             .forEach(x -> {
                delete(x);
            });
    }

    public void delete(Path x) {
        log.info("Deleting {}", x);
        try {
            PathUtils.delete(x);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createSingleCatalogsFor(String domainName,
                                        SupportedCloudsEnum catalog,
                                        Properties properties) throws Exception {
        properties.put(POLARIS_ENDPOINT.getPropertyName(), domainName);
        executeMainOf(EndpointPolarisConfiguration.class,
                      new String[] {catalog.name()},
                      properties);
    }

    private static void restoreSystemProperties(Properties properties, Properties original) {
        properties.keySet().forEach(propKey -> {
            String key   = propKey.toString();
            String value = original.getProperty(key);
            log.info("Restoring system property {} = {}", key, value);
            if (value != null) {
                System.setProperty(key, value);
            }  else {
                System.clearProperty(key);
            }
        });
    }

    private static @NonNull Properties saveAndSetSystem(Properties properties) {
        Properties original = new Properties();
        properties.keySet().forEach(mapKey -> {
            String key          = mapKey.toString();
            String systemProp   = System.getProperty(key);
            if (systemProp != null) {
                original.put(key, systemProp);
            }
            String value = properties.getProperty(key);
            log.info("Setting system property {} = {}", key, value);
            System.setProperty(key, value);
        });
        return original;
    }

    public void createSingleCatalogsFor(String domainName,
                                        SupportedCloudsEnum catalog) throws Exception {
        createSingleCatalogsFor(domainName, catalog, new Properties());
    }

}
