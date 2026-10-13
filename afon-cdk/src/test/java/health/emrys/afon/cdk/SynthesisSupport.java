package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static health.emrys.afon.config.ConfigUtils.AFON_CDKTF_PROPERTY;
import static health.emrys.afon.config.ConfigUtils.AFON_HOME_PROPERTY;

/**
 * Synthesises the plan of a stack from the template config, with fixed values for what it leaves blank, as
 * the test of the determinism of the synthesis did.  It also runs on its own,
 * in a JVM of its own, to synthesise the plans of the stacks named by the args into the dir.
 */
public class SynthesisSupport {

    private static final String TEMPLATES_CONFIG_DIR = "../templates/config";

    /**
     * What the template config leaves blank, but the stacks need, set as system properties, which override the config
     * before its references are resolved, eg of ENV_NAME.  The properties that are optional are left blank.
     */
    private static final Map<String, String> FIXED_VALUES = fixedValues();

    private static Map<String, String> fixedValues() {
        Map<String, String> values = new TreeMap<>(Map.of(
            "ENV_NAME", "golden",
            "USER_ID", "mauro",
            "AWS_REGION", "eu-west-2",
            "AZURE_REGION", "uksouth",
            "AWS_POLARIS_DOMAIN_NAME", "polaris.golden.example",
            "AWS_JUPYTER_DOMAIN_NAME", "jupyter.golden.example",
            "AZURE_POLARIS_DOMAIN_NAME", "polaris-azure.golden.example",
            "AZURE_JUPYTER_DOMAIN_NAME", "jupyter-azure.golden.example",
            "GCP_POLARIS_DOMAIN_NAME", "polaris-gcp.golden.example",
            "GCP_JUPYTER_DOMAIN_NAME", "jupyter-gcp.golden.example"));
        for (String name : List.of(
            "AZURE_SUBSCRIPTION", "AWS_ACCOUNT_ID", "AWS_S3_ROLE_ARN", "AWS_GLUE_ROLE_ARN", "AWS_POLARIS_DNS_ZONE",
            "AWS_JUPYTER_DNS_ZONE", "AWS_KEYCLOAK_DNS_ZONE", "AWS_KEYCLOAK_CERT_ARN", "AWS_JUPYTER_CERT_ARN",
            "AWS_POLARIS_CERT_ARN", "GCP_PROJECT_ID", "GCP_SERVICE_ACCOUNT", "GCP_USER_ACCOUNT", "AZURE_TENANT_ID",
            "AZURE_APP_ID", "AZURE_APP_SECRET", "AZURE_SP_ID", "AZURE_CONNECTION_STRING", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
            "POLARIS_DB_HOST", "POLARIS_DB_PASSWORD", "POLARIS_ID", "POLARIS_SECRET", "KEYCLOAK_BOOTSTRAP_ADMIN_USERNAME",
            "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD", "KEYCLOAK_DB_PASSWORD", "GUACAMOLE_DB_HOST", "GUACAMOLE_DB_USERNAME",
            "GUACAMOLE_DB_PASSWORD", "OAUTH2_CLIENT_ID", "OAUTH2_CLIENT_SECRET", "OAUTH2_COOKIE_SECRET",
            "JUPYTER_VNC_PASSWORD")) {
            values.put(name, "golden-" + name.toLowerCase().replace('_', '-'));
        }
        // production code parses it, eg for Gitea's blob storage, so it must be well-formed
        values.put("AZURE_CONNECTION_STRING",
                   "DefaultEndpointsProtocol=https;AccountName=goldenafonstorage;AccountKey=golden-azure-account-key;"
                   + "EndpointSuffix=core.windows.net");
        return values;
    }

    /**
     * @return the plan of the stack, as synthesised, ie as it goes to tofu
     */
    public static String synthesise(StackId stackId, Path outDir) throws IOException {
        return synthesise(stackId, outDir, Map.of());
    }

    /**
     * @param overrides config values set on top of the fixed ones, eg of an optional property the template leaves blank
     * @return the plan of the stack, as synthesised, ie as it goes to tofu
     */
    public static String synthesise(StackId stackId, Path outDir, Map<String, String> overrides) throws IOException {
        Map<String, String> systemProperties = new TreeMap<>(FIXED_VALUES);
        systemProperties.putAll(overrides);
        // the tofu state path is part of the plan
        systemProperties.put(AFON_CDKTF_PROPERTY, "/golden/cdktf");
        // what is read from the afon home, eg the users, is the template's, not the developer's own
        systemProperties.put(AFON_HOME_PROPERTY, templatesHome().toString());
        systemProperties.forEach(System::setProperty);
        try {
            AfonPropertyProvider properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
            App app = new App(AppConfig.builder().outdir(outDir.toString()).build());
            new ComposedStack(app, StackDefinitions.get(stackId), properties);
            app.synth();
        } finally {
            systemProperties.keySet().forEach(System::clearProperty);
        }
        return Files.readString(outDir.resolve("stacks").resolve(stackId.toString()).resolve("cdk.tf.json"));
    }


    private static Path templatesHome() throws IOException {
        Path home = Files.createTempDirectory("afon-home");
        home.toFile().deleteOnExit();
        Path config = Files.createSymbolicLink(home.resolve("config"), Path.of(TEMPLATES_CONFIG_DIR).toAbsolutePath().normalize());
        config.toFile().deleteOnExit();
        return home;
    }

    /**
     * @param args the dir to write the plans of the stacks to, as {@code <stack-id>.tf.json}, then the ids of the stacks
     */
    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args[0]);
        for (int i = 1; i < args.length; i++) {
            StackId stackId = StackId.parse(args[i]);
            Path    work    = Files.createTempDirectory("synthesis");
            Files.writeString(outDir.resolve(stackId + ".tf.json"), synthesise(stackId, work));
        }
    }
}
