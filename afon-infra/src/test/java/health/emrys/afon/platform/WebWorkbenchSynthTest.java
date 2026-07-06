package health.emrys.afon.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.config.deployment.AfonDeploymentConfig;
import health.emrys.afon.config.deployment.CloudSpec;
import health.emrys.afon.config.deployment.DeploymentConfigParser;
import health.emrys.afon.config.deployment.WorkbenchSpec;
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
 * Phase 3: VS Code and RStudio workbenches synthesise the shared chain
 * ingress → oauth2-proxy ({@code AuthProxyConfig}) → &lt;type&gt;-web →
 * workbench pod, with the workbench's internal auth disabled so oauth2-proxy
 * is the sole gate. Same environment expectations as
 * {@code DeploymentPlannerTest}.
 */
public class WebWorkbenchSynthTest {

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
    public void vsCodeSynthesisesBehindOauth2Proxy() throws IOException {
        JsonNode resources = synthAwsWith(WorkbenchSpec.TYPE_VSCODE, tmp.resolve("vscode"));

        JsonNode container = workbenchContainer(resources, "vscodeDeployment");
        assertThat(container.get("image").asText()).startsWith("codercom/code-server");
        assertThat(container.get("args").toString()).contains("--auth=none");

        assertProxiedAndExposed(resources, "vscode", VsCodeWorkbenchDeployer.PORT);
    }

    @Test
    public void rStudioSynthesisesBehindOauth2Proxy() throws IOException {
        JsonNode resources = synthAwsWith(WorkbenchSpec.TYPE_RSTUDIO, tmp.resolve("rstudio"));

        JsonNode container = workbenchContainer(resources, "rstudioDeployment");
        assertThat(container.get("image").asText()).startsWith("rocker/rstudio");
        assertThat(container.get("env").toString()).contains("DISABLE_AUTH");

        assertProxiedAndExposed(resources, "rstudio", RStudioWorkbenchDeployer.PORT);
    }

    @Test
    public void webWorkbenchesRequireAnAuthBlock() throws IOException {
        AfonDeploymentConfig config = withWorkbench(parseDemo(), WorkbenchSpec.TYPE_VSCODE, false);
        App app = new App(AppConfig.builder().outdir(tmp.resolve("no-auth").toString()).build());
        assertThatThrownBy(() -> new DeploymentPlanner().synthesise(
            app, awsCloud(config), config,
            DeploymentContext.of(config, new AfonPropertiesLoader().getProperties())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("auth");
    }

    /** The shared chain: client-credentials Secret, proxy Deployment/Service, web Service, ingress. */
    private void assertProxiedAndExposed(JsonNode resources, String name, int port) {
        String proxy = name + "-oauth2-proxy";

        assertThat(resources.at("/kubernetes_secret/" + proxy + "Secret/metadata/name").asText())
            .isEqualTo(proxy + "-secret");

        JsonNode proxyContainer = workbenchContainer(resources, proxy + "Deployment");
        assertThat(proxyContainer.get("image").asText()).startsWith("quay.io/oauth2-proxy/oauth2-proxy");
        String proxyArgs = proxyContainer.get("args").toString();
        assertThat(proxyArgs).contains("--upstream=http://" + name + "-web:" + port);
        assertThat(proxyArgs).contains("--redirect-url=https://emrysjupyter.click/oauth2/callback");

        assertThat(resources.at("/kubernetes_service/" + name + "-web/spec/port/0/port").asInt())
            .isEqualTo(port);
        assertThat(resources.at("/kubernetes_service/" + proxy + "/spec/port/0/port").asInt())
            .isEqualTo(4180);

        // The ALB ingress routes the workbench domain to the proxy. It is
        // scoped under the AwsConstruct child construct, so its Terraform key
        // is prefixed and hash-suffixed — match by id substring.
        JsonNode ingresses = resources.get("kubernetes_ingress_v1");
        assertThat(ingresses).isNotNull();
        JsonNode ingress = null;
        var ids = ingresses.fieldNames();
        while (ids.hasNext()) {
            String id = ids.next();
            if (id.contains(name + "-ingress-https-ingress")) {
                ingress = ingresses.get(id);
            }
        }
        assertThat(ingress)
            .withFailMessage("No '%s-ingress-https-ingress' among ingresses in synthesised stack", name)
            .isNotNull();
        assertThat(ingress.toString()).contains(proxy);
    }

    private static JsonNode workbenchContainer(JsonNode resources, String deploymentId) {
        JsonNode container = resources.at(
            "/kubernetes_deployment/" + deploymentId + "/spec/template/spec/container/0");
        assertThat(container.isMissingNode())
            .withFailMessage("No deployment '%s' in synthesised stack", deploymentId)
            .isFalse();
        return container;
    }

    /** Synthesises the demo's AWS cloud with its workbench swapped for {@code type}. */
    private JsonNode synthAwsWith(String type, Path outDir) throws IOException {
        Properties           properties = new AfonPropertiesLoader().getProperties();
        AfonDeploymentConfig config     = withWorkbench(parseDemo(), type, true);

        App app = new App(AppConfig.builder().outdir(outDir.toString()).build());
        new DeploymentPlanner().synthesise(
            app, awsCloud(config), config, DeploymentContext.of(config, properties));
        app.synth();

        JsonNode stack = new ObjectMapper().readTree(
            Files.readString(outDir.resolve("stacks/aws/cdk.tf.json")));
        return stack.get("resource");
    }

    private static AfonDeploymentConfig withWorkbench(AfonDeploymentConfig demo, String type, boolean keepAuth) {
        WorkbenchSpec jupyter = demo.workbench();
        WorkbenchSpec swapped = new WorkbenchSpec(
            type, jupyter.target(), jupyter.domain(), keepAuth ? jupyter.auth() : null);
        return new AfonDeploymentConfig(
            demo.apiVersion(), demo.deployment(), demo.clouds(), demo.catalogs(),
            demo.queryEngines(), swapped, demo.secretsFile());
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
