package health.emrys.afon.platform;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import health.emrys.afon.cdk.aws.AwsKubernetesPolaris;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.config.deployment.AfonDeploymentConfig;
import health.emrys.afon.config.deployment.CloudSpec;
import health.emrys.afon.config.deployment.DeploymentConfigParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 gate (docs/config_driven_architecture.md §7.1): composing a cloud via
 * {@link CloudProviderPlugin} must synthesise byte-identical Terraform JSON to
 * the legacy stack it wraps. Guards against accidental construct-id drift,
 * which would make {@code tofu apply} recreate live resources.
 * <p>
 * Like {@code PolarisConfigTest}, this uses the local {@code afon.properties} /
 * {@code env.properties}; KeyCloak values are defaulted if absent so the test
 * runs on machines that have not yet added them.
 */
public class PluginParityTest {

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
    public void awsPluginSynthesisesIdenticallyToLegacyStack() throws IOException {
        Properties properties = new AfonPropertiesLoader().getProperties();

        // helmProvider(): AwsConstruct declares helm releases but the provider
        // construct historically only appears in AwsWholeStack; created here on
        // both paths so the bare stack passes cdktf validation.
        Path legacyOut = tmp.resolve("legacy");
        App  legacyApp = new App(AppConfig.builder().outdir(legacyOut.toString()).build());
        new AwsKubernetesPolaris(legacyApp, AwsKubernetesPolaris.ID, properties).helmProvider();
        legacyApp.synth();

        Path pluginOut = tmp.resolve("plugin");
        App  pluginApp = new App(AppConfig.builder().outdir(pluginOut.toString()).build());
        PluginRegistry.standard()
                      .cloudPlugin(CloudSpec.PROVIDER_AWS)
                      .provision(pluginApp, awsSpec(), DeploymentContext.legacy(properties))
                      .helmProvider();
        pluginApp.synth();

        String legacyJson = Files.readString(legacyOut.resolve("stacks/aws/cdk.tf.json"));
        String pluginJson = Files.readString(pluginOut.resolve("stacks/aws/cdk.tf.json"));
        assertThat(pluginJson).isEqualTo(legacyJson);
    }

    @Test
    public void pluginRejectsNonLegacyCloudIds() {
        Properties properties = new AfonPropertiesLoader().getProperties();
        App        app        = new App(AppConfig.builder().outdir(tmp.resolve("reject").toString()).build());
        CloudSpec  renamed    = new CloudSpec(
            "aws-primary", CloudSpec.PROVIDER_AWS, "eu-west-2", Map.of(), Map.of());
        assertThatThrownBy(() -> PluginRegistry.standard()
                                               .cloudPlugin(CloudSpec.PROVIDER_AWS)
                                               .provision(app, renamed, DeploymentContext.legacy(properties)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("aws-primary");
    }

    private static CloudSpec awsSpec() throws IOException {
        AfonDeploymentConfig config = new DeploymentConfigParser()
            .parse(Path.of("..", "docs", "examples", "emrys-demo.yaml"));
        return config.clouds().stream()
                     .filter(c -> CloudSpec.PROVIDER_AWS.equals(c.provider()))
                     .findFirst()
                     .orElseThrow();
    }
}
