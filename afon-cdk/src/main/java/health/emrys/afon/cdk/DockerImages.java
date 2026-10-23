package health.emrys.afon.cdk;

import health.emrys.afon.cdk.configs.TrinoWorkloads;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Properties;

/**
 * Single source of truth for every Docker image deployed or spinned up in tests, so a version bump is a
 * one-line change in {@code docker-images.yaml} (see {@link DockerImageDefinitions}) instead of a grep across CDK
 * constructs and {@code afon-acceptance}'s testcontainers.
 *
 * <p>{@link #POLARIS_IMAGE} and {@link #POLARIS_ADMIN_TOOL_IMAGE} are the one case where the
 * image actually corresponds to a Maven artifact this project builds against ({@code
 * polaris-core}, via the root pom's {@code polaris.version}). Their tag in the YAML is the placeholder {@code
 * ${polaris.version}}, resolved from {@code versions.properties}, which Maven resource filtering stamps from that same
 * property (see afon-cdk/pom.xml), so the two can never drift apart silently. The rest are unrelated
 * images with no corresponding Maven dependency - centralized here for reuse, not
 * for cross-build version sync.
 */
public final class DockerImages {

    private static final Properties VERSIONS = loadVersions();
    private static final Map<String, DockerImageDefinitions.Definition> IMAGES = DockerImageDefinitions.load(VERSIONS::getProperty);

    public static final String POLARIS_VERSION = loadVersion("polaris.version");

    public static final String POLARIS_IMAGE             = reference("polaris");
    public static final String POLARIS_ADMIN_TOOL_IMAGE  = reference("polaris-admin-tool");

    public static final String KEYCLOAK_IMAGE            = reference("keycloak");

    public static final String SPARK_IMAGE               = reference("spark");
    public static final String PYSPARK_NOTEBOOK_IMAGE    = reference("pyspark-notebook");

    public static final String GITEA_IMAGE               = reference("gitea");

    public static final String JUPYTERHUB_HUB_IMAGE      = reference("jupyterhub-hub");
    public static final String JUPYTERHUB_PROXY_IMAGE    = reference("jupyterhub-proxy");

    public static final String GUACAMOLE_IMAGE           = reference("guacamole");
    public static final String GUACD_IMAGE               = reference("guacd");

    public static final String OAUTH2_PROXY_IMAGE        = reference("oauth2-proxy");
    public static final String CURL_IMAGE                = reference("curl");

    public static final String POSTGRES_ALPINE_IMAGE     = reference("postgres-alpine");

    /**
     * Overrides the appVersion of the vendored Trino Helm chart (see {@code
     * TrinoWorkloads#CHART_VERSION}, which pins the chart itself - a separate axis from the image
     * tag set here). The README sanity check expects {@code select version()} to report this.
     */
    public static final String TRINO_IMAGE_TAG           = definition("trino").tag();

    private DockerImages() {}

    /**
     * @return the images by name, as defined in {@code docker-images.yaml}
     */
    public static Map<String, DockerImageDefinitions.Definition> all() {
        return IMAGES;
    }

    /**
     * @return the value of the key in {@code versions.properties}, which the {@code ${key}} placeholders of the tags are, or {@code null}
     */
    public static String version(String key) {
        return VERSIONS.getProperty(key);
    }

    private static DockerImageDefinitions.Definition definition(String name) {
        DockerImageDefinitions.Definition definition = IMAGES.get(name);
        if (definition == null) {
            throw new IllegalStateException("No Docker image '" + name + "' defined in " + DockerImageDefinitions.IMAGES_FILE);
        }
        return definition;
    }

    private static String reference(String name) {
        return definition(name).reference();
    }

    private static Properties loadVersions() {
        Properties properties = new Properties();
        try (InputStream is = DockerImages.class.getResourceAsStream("/versions.properties")) {
            if (is == null) {
                throw new IllegalStateException("versions.properties missing from the classpath");
            }
            properties.load(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    private static String loadVersion(String versionKey) {
        String version = VERSIONS.getProperty(versionKey);
        if (version == null || version.startsWith("${")) {
            throw new IllegalStateException(
                versionKey + " in versions.properties was not substituted by Maven resource filtering");
        }
        return version;
    }
}
