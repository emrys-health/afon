package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsWholeStack;
import health.emrys.afon.cdk.AzureKubernetesPolaris;
import health.emrys.afon.cdk.GcpKubernetesPolaris;
import health.emrys.afon.cdk.SparkClusterDeployment;
import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;
import health.emrys.afon.core.configuration.LocalPolarisRegisterMain;
import health.emrys.afon.infra.EndpointPolarisConfiguration;
import health.emrys.afon.snowflake.SmokeTest;
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
import java.util.Arrays;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
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
        int pause = 5000;
        log.info("Waiting {}s. Maximum = {}s", pause / 1000, timeout / 1000);
        try {
            Thread.sleep(pause);
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
            run("tofu", path, "destroy", "--auto-approve");
        } catch (Exception e) {
            log.error("Failed to call tofu destroy in " + path, e);
        }
    }

    public @NonNull Path cdktfPath(SupportedCloudsEnum id) {
        return Path.of("cdktf.out", "stacks", id.toString());
    }

    public void startGcpPolaris() throws Exception {
        executeMainOf(GcpKubernetesPolaris.class, new String[]{});
        startCloud(cdktfPath(GcpKubernetesPolaris.ID));
    }

    public void startAzurePolaris() throws Exception {
        executeMainOf(AzureKubernetesPolaris.class, new String[]{});
        startCloud(cdktfPath(AzureKubernetesPolaris.ID));
    }

    public void startSparkClusterInAws() throws Exception {
        executeMainOf(SparkClusterDeployment.class, new String[]{});
        startCloud(cdktfPath(SparkClusterDeployment.ID));
    }

    public void startAwsPolaris() throws Exception {
        executeMainOf(AwsWholeStack.class, new String[]{});
        startCloud(cdktfPath(AwsWholeStack.ID));
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

    public void startCloud(Path jsonDirectory) throws ManagedProcessException {
        run("tofu", jsonDirectory, "init");
        OutputStream suppressedStdOut = OutputStream.nullOutputStream();
        run("tofu", jsonDirectory, suppressedStdOut, "apply", "--auto-approve");
    }

    public void run(String command, Path path, String... commands) throws ManagedProcessException {
        run(command, path, System.out, commands);
    }

    public void run(String command, Path path, OutputStream stdOut, String... commands) throws ManagedProcessException {
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
    }

    public void deleteTerraformStateFiles(SupportedCloudsEnum id) throws IOException {
        deleteTerraformIn(".", id);
        deleteTerraformIn("..", id);
    }

    public void readFromSnowflake(String table, Properties properties) throws Exception {
        new SmokeTest().selectFrom(table, properties);
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
