import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the {@code *Main} script command classes under {@code health.emrys.afon.ops.scripts.<cloud>},
 * one per entry in {@link #SCRIPTS} below, each delegating to {@code ScriptMain}.
 * <p>
 * Run at build time (see the {@code generate-sources} binding in afon-ops/pom.xml) as a single-file
 * source program: {@code java GenerateScriptMains.java <output-dir>}.  The generated sources are not
 * checked in - to add a command for a new script, add an entry to {@link #SCRIPTS} and rebuild.
 * <p>
 * "Single-file source program" refers to JEP 330 ("Launch Single-File Source-Code Programs", JDK 11),
 * which lets {@code java} compile and run a {@code .java} file directly with no separate {@code javac}
 * step and no dependency on this module's own classpath - exactly what lets this generator run in the
 * {@code generate-sources} phase, before afon-ops itself has been compiled.
 */
public class GenerateScriptMains {

    /**
     * @param stackCloud       the cloud of the script, if it takes a stack, given as its id or its role in the cloud, in which
     *                         case {@code ScriptMain} finds the stack and gives the script what it needs, as {@code AFON_STACK_*}
     *                         env vars
     * @param servingComponent the component, if the script checks the stack serving it, which it is given, else
     *                         {@code ScriptMain} gives the stacks that can serve it, as {@code AFON_STACK_CANDIDATES}
     */
    private record ScriptEntry(String subpackage, String className, String scriptPath, String stackCloud,
                               String servingComponent) {
        ScriptEntry(String subpackage, String className, String scriptPath) {
            this(subpackage, className, scriptPath, null, null);
        }

        ScriptEntry(String subpackage, String className, String scriptPath, String stackCloud) {
            this(subpackage, className, scriptPath, stackCloud, null);
        }
    }

    private static final List<ScriptEntry> SCRIPTS = List.of(
        new ScriptEntry("config", "ConfigInitMain", "config/init.sh"),
        new ScriptEntry("config", "ConfigStatusMain", "config/status.sh"),
        new ScriptEntry("config", "ConfigSwitchMain", "config/switch.sh"),
        new ScriptEntry("config", "ConfigValidateMain", "config/validate.sh"),
        new ScriptEntry("config", "ConfigExportMain", "config/export.sh"),
        new ScriptEntry("config", "ConfigImportMain", "config/import.sh"),
        new ScriptEntry("config", "ConfigUpdatesMain", "config/updates.sh"),
        new ScriptEntry("config", "ConfigUserMain", "config/user.sh"),
        new ScriptEntry("config", "ConfigVaultMain", "config/vault.sh"),

        new ScriptEntry("cdktf", "CdktfInitMain", "cdktf/init.sh"),
        new ScriptEntry("cdktf", "CdktfStatusMain", "cdktf/status.sh"),
        new ScriptEntry("cdktf", "CdktfSwitchMain", "cdktf/switch.sh"),

        new ScriptEntry("tofu", "TofuApplyMain", "tofu/apply.sh"),
        new ScriptEntry("tofu", "TofuDestroyMain", "tofu/destroy.sh"),
        new ScriptEntry("tofu", "TofuPlanMain", "tofu/plan.sh"),
        new ScriptEntry("tofu", "TofuStateMain", "tofu/state.sh"),

        new ScriptEntry("dns", "DnsCreateZonesMain", "dns/create-zones.sh"),
        new ScriptEntry("dns", "DnsDelegateMain", "dns/delegate.sh"),

        new ScriptEntry("aws", "AwsAccountMain", "aws/account.sh"),
        new ScriptEntry("aws", "AwsClusterMain", "aws/cluster.sh", "aws"),
        new ScriptEntry("aws", "AwsCreateFederatedPermissionsMain", "aws/create_federated_permissions.sh"),
        new ScriptEntry("aws", "AwsCreateMain", "aws/create.sh", "aws"),
        new ScriptEntry("aws", "AwsDestroyMain", "aws/destroy.sh"),
        new ScriptEntry("aws", "AwsDnsZonesMain", "aws/dns-zones.sh"),
        new ScriptEntry("aws", "AwsEgressIpMain", "aws/egress-ip.sh"),
        new ScriptEntry("aws", "AwsIssueCertMain", "aws/issue-cert.sh"),
        new ScriptEntry("aws", "AwsKeycloakMain", "aws/keycloak.sh", "aws", "Keycloak"),
        new ScriptEntry("aws", "AwsLoginMain", "aws/login.sh"),
        new ScriptEntry("aws", "AwsMountDataMain", "aws/mount-data.sh"),
        new ScriptEntry("aws", "AwsPolarisMain", "aws/polaris.sh", "aws", "Polaris"),
        new ScriptEntry("aws", "AwsSnowflakeMain", "aws/snowflake.sh"),
        new ScriptEntry("aws", "AwsUpdateKubeConfigMain", "aws/update-kube-config.sh", "aws"),
        new ScriptEntry("aws", "AwsVpcsMain", "aws/vpcs.sh", "aws"),

        new ScriptEntry("azure", "AzureAccountPrincipalMain", "azure/account-principal.sh"),
        new ScriptEntry("azure", "AzureAccountMain", "azure/account.sh"),
        new ScriptEntry("azure", "AzureCheckStorageMain", "azure/check-storage.sh"),
        new ScriptEntry("azure", "AzureClusterMain", "azure/cluster.sh", "azure"),
        new ScriptEntry("azure", "AzureCreateAdminMain", "azure/create-admin.sh"),
        new ScriptEntry("azure", "AzureCreateMain", "azure/create.sh", "azure"),
        new ScriptEntry("azure", "AzureDestroyAdminMain", "azure/destroy-admin.sh"),
        new ScriptEntry("azure", "AzureDestroyMain", "azure/destroy.sh"),
        new ScriptEntry("azure", "AzureDnsZonesMain", "azure/dns-zones.sh"),
        new ScriptEntry("azure", "AzureIssueCertMain", "azure/issue-cert.sh"),
        new ScriptEntry("azure", "AzureKeycloakMain", "azure/keycloak.sh", "azure", "Keycloak"),
        new ScriptEntry("azure", "AzureKeyvaultMain", "azure/keyvault.sh"),
        new ScriptEntry("azure", "AzureLoginAppMain", "azure/login-app.sh"),
        new ScriptEntry("azure", "AzureLoginMain", "azure/login.sh"),
        new ScriptEntry("azure", "AzurePolarisMain", "azure/polaris.sh", "azure", "Polaris"),
        new ScriptEntry("azure", "AzureEgressIpMain", "azure/egress-ip.sh"),
        new ScriptEntry("azure", "AzureRbacMain", "azure/rbac.sh"),
        new ScriptEntry("azure", "AzureRolesMain", "azure/roles.sh"),
        new ScriptEntry("azure", "AzureRollingUpgradesMain", "azure/rolling-upgrades.sh", "azure"),
        new ScriptEntry("azure", "AzureUpdateKubeConfigMain", "azure/update-kube-config.sh", "azure"),

        new ScriptEntry("gcp", "GcpAccountMain", "gcp/account.sh"),
        new ScriptEntry("gcp", "GcpCreateIcebergEndpointMain", "gcp/create_iceberg_endpoint.sh"),
        new ScriptEntry("gcp", "GcpCreateMain", "gcp/create.sh", "gcp"),
        new ScriptEntry("gcp", "GcpDestroyMain", "gcp/destroy.sh"),
        new ScriptEntry("gcp", "GcpDnsZonesMain", "gcp/dns-zones.sh"),
        new ScriptEntry("gcp", "GcpEgressIpMain", "gcp/egress-ip.sh"),
        new ScriptEntry("gcp", "GcpIssueCertMain", "gcp/issue-cert.sh"),
        new ScriptEntry("gcp", "GcpKeycloakMain", "gcp/keycloak.sh", "gcp", "Keycloak"),
        new ScriptEntry("gcp", "GcpLoginAsServiceAccountMain", "gcp/login-as-service-account.sh"),
        new ScriptEntry("gcp", "GcpLoginMain", "gcp/login.sh"),
        new ScriptEntry("gcp", "GcpPolarisMain", "gcp/polaris.sh", "gcp", "Polaris"),
        new ScriptEntry("gcp", "GcpSecretsMain", "gcp/secrets.sh"),
        new ScriptEntry("gcp", "GcpUpdateKubeConfigMain", "gcp/update-kube-config.sh", "gcp"),

        new ScriptEntry("github", "GithubMilestonesMain", "github/milestones.sh"),
        new ScriptEntry("github", "GithubRunnerMain", "github/runner.sh"),
        new ScriptEntry("github", "GithubSecretsMain", "github/secrets.sh"),

        new ScriptEntry("data", "DataSyntheticEhrMain", "data/synthetic-ehr.sh"),

        new ScriptEntry("jupyter", "JupyterExecuteMain", "jupyter/execute.sh"),

        new ScriptEntry("python", "PythonSetupMain", "python/setup.sh"),

        new ScriptEntry("local", "LocalSetupMain", "local/setup.sh"),
        new ScriptEntry("local", "LocalVersionsMain", "local/versions.sh"),

        new ScriptEntry("kube", "KubeEstimatorMain", "kube/estimator.sh"),
        new ScriptEntry("kube", "KubePodsMain", "kube/pods.sh"),
        new ScriptEntry("kube", "KubeLogsMain", "kube/logs.sh"),
        new ScriptEntry("kube", "KubePolarisDbMain", "kube/polaris-db.sh"),
        new ScriptEntry("kube", "KubePolarisWhitelistMain", "kube/polaris-whitelist.sh"),

        new ScriptEntry("signing", "SigningPublicKeyMain", "signing/public-key.sh"),
        new ScriptEntry("signing", "SigningReportMain", "signing/report.sh"),

        new ScriptEntry("smoke", "SmokeNotebookMain", "smoke/notebook.sh"),
        new ScriptEntry("smoke", "SmokeSigningMain", "smoke/signing.sh"),
        new ScriptEntry("smoke", "SmokeTestMain", "smoke/test.sh")
    );

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java GenerateScriptMains.java <output-dir>");
            System.exit(1);
        }
        Path outputDir = Path.of(args[0]);
        for (ScriptEntry entry : SCRIPTS) {
            write(outputDir, entry);
        }
        System.out.println("Generated " + SCRIPTS.size() + " script command classes in " + outputDir);
    }

    private static void write(Path outputDir, ScriptEntry entry) throws IOException {
        Path packageDir = outputDir.resolve("health/emrys/afon/ops/scripts/" + entry.subpackage());
        Files.createDirectories(packageDir);
        String source = """
            package health.emrys.afon.ops.scripts.%s;

            import health.emrys.afon.ops.scripts.ScriptMain;

            // Generated by GenerateScriptMains.java - do not edit directly.
            public class %s {

                public static void main(String[] args) {
                    new ScriptMain("%s")%s%s.run(args);
                }

            }
            """.formatted(entry.subpackage(), entry.className(), entry.scriptPath(),
                          entry.stackCloud() == null ? "" : ".takingStackOf(\"" + entry.stackCloud() + "\")",
                          entry.servingComponent() == null ? "" : ".servingComponent(\"" + entry.servingComponent() + "\")");
        Files.writeString(packageDir.resolve(entry.className() + ".java"), source);
    }

}
