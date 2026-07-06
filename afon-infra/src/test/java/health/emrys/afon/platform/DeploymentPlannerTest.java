package health.emrys.afon.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import health.emrys.afon.cdk.aws.AwsKubernetesPolaris;
import health.emrys.afon.cdk.aws.SparkClusterDeployment;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.config.deployment.AfonDeploymentConfig;
import health.emrys.afon.config.deployment.CloudSpec;
import health.emrys.afon.config.deployment.DeploymentConfigParser;
import health.emrys.afon.demo.StepActions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 gates (docs/config_driven_architecture.md §7.1):
 * <ul>
 *   <li>the plan for the demo config has the same shape as the legacy
 *       {@code DeployFullStackAwsMain}-family step lists;</li>
 *   <li>the config-driven AWS synth is semantically identical to the legacy
 *       {@code SparkClusterDeployment} (the full AWS deployment) — same
 *       resources under the same Terraform addresses, so {@code tofu apply}
 *       over existing state is a no-op;</li>
 *   <li>Snowflake resolves through the registry and yields the demo's
 *       catalog-integration SQL as runtime steps.</li>
 * </ul>
 * Same environment expectations as {@code PluginParityTest} (local
 * {@code afon.properties}; KeyCloak values defaulted).
 */
public class DeploymentPlannerTest {

    private static final Path DEMO_YAML = Path.of("..", "docs", "examples", "emrys-demo.yaml");

    @TempDir
    static Path tmp;

    @BeforeAll
    public static void defaultKeyCloakProperties() {
        Map.of(
            "KC_HOSTNAME", "kc.test",
            "KC_DB_URL", "jdbc:postgresql://kc-db/kc",
            "KC_DB_USERNAME", "kc",
            "KC_DB_PASSWORD", "kcpw",
            "KC_DB", "kc",
            "KC_BOOTSTRAP_ADMIN_USERNAME", "admin",
            "KC_BOOTSTRAP_ADMIN_PASSWORD", "adminpw"
        ).forEach((k, v) -> {
            if (System.getProperty(k) == null) {
                System.setProperty(k, v);
            }
        });
    }

    @Test
    public void plansTheDemoConfigInLegacyOrder() throws IOException {
        DeploymentPlan plan = new DeploymentPlanner().plan(
            parseDemo(), new AfonPropertiesLoader().getProperties(), new StepActions());

        assertThat(plan.stepNames()).containsExactly(
            "clean_aws", "deploy_aws", "waitFor_emryspolaris", "all_catalogs_in_aws",
            "clean_azure", "deploy_azure", "waitFor_emryspolarisazure", "all_catalogs_in_azure",
            "clean_gcp", "deploy_gcp", "waitFor_emryspolarisgcp", "all_catalogs_in_gcp",
            "emryspolaris_spark_talks_to_aws",
            "snowflake_integration_aws", "snowflake_integration_gcp", "snowflake_integration_azure_external");
    }

    @Test
    public void configDrivenAwsSynthMatchesLegacyFullDeployment() throws IOException {
        Properties           properties = new AfonPropertiesLoader().getProperties();
        AfonDeploymentConfig config     = parseDemo();

        Path legacyOut = tmp.resolve("legacy");
        App  legacyApp = new App(AppConfig.builder().outdir(legacyOut.toString()).build());
        new SparkClusterDeployment(legacyApp, AwsKubernetesPolaris.ID, properties);
        legacyApp.synth();

        Path plannerOut = tmp.resolve("planner");
        App  plannerApp = new App(AppConfig.builder().outdir(plannerOut.toString()).build());
        new DeploymentPlanner().synthesise(
            plannerApp, awsCloud(config), config, DeploymentContext.of(config, properties));
        plannerApp.synth();

        // Construct creation order differs between the two paths (the legacy
        // stack deploys Jupyter first, the planner last), which permutes JSON
        // key order but not content — so compare trees, not bytes. JsonNode
        // equality ignores object key order but is strict about array order.
        ObjectMapper mapper      = new ObjectMapper();
        JsonNode     legacyJson  = mapper.readTree(Files.readString(legacyOut.resolve("stacks/aws/cdk.tf.json")));
        JsonNode     plannerJson = mapper.readTree(Files.readString(plannerOut.resolve("stacks/aws/cdk.tf.json")));
        assertThat(plannerJson).isEqualTo(legacyJson);
    }

    @Test
    public void snowflakeRuntimeStepsCarryTheDemoIntegrationSql() {
        String sql = SnowflakeEngineDeployer.catalogIntegrationSql(
            "emryspolaris.click", "azure_external", "client-id", "client-secret");
        assertThat(sql)
            .contains("CREATE OR REPLACE CATALOG INTEGRATION afon_azure_external")
            .contains("CATALOG_URI = 'https://emryspolaris.click/api/catalog/'")
            .contains("CATALOG_NAME = 'azure_external'")
            .contains("OAUTH_CLIENT_ID = 'client-id'")
            .contains("OAUTH_CLIENT_SECRET = '***REMOVED***'");
    }

    private static AfonDeploymentConfig parseDemo() throws IOException {
        return new DeploymentConfigParser().parse(DEMO_YAML);
    }

    private static CloudSpec awsCloud(AfonDeploymentConfig config) {
        return config.clouds().stream()
                     .filter(c -> CloudSpec.PROVIDER_AWS.equals(c.provider()))
                     .findFirst()
                     .orElseThrow();
    }
}
