package health.emrys.afon.cdk;

import health.emrys.afon.cdk.configs.TrinoWorkloads;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Single source of truth for every third-party Docker image deployed or spinned up in
 * tests, so a version bump is a one-line change here instead of a grep across CDK constructs and
 * {@code afon-acceptance}'s testcontainers.
 *
 * <p>{@link #POLARIS_IMAGE} and {@link #POLARIS_ADMIN_TOOL_IMAGE} are the one case where the
 * image actually corresponds to a Maven artifact this project builds against ({@code
 * polaris-core}, via the root pom's {@code polaris.version}). {@link #POLARIS_VERSION} is read from
 * {@code versions.properties}, which Maven resource filtering stamps from that same property (see
 * afon-cdk/pom.xml), so the two can never drift apart silently. The rest are unrelated
 * third-party images with no corresponding Maven dependency - centralized here for reuse, not
 * for cross-build version sync.
 */
public final class DockerImages {

    public static final String POLARIS_VERSION = loadVersion("polaris.version");

    public static final String POLARIS_IMAGE             = "apache/polaris:" + POLARIS_VERSION;
    public static final String POLARIS_ADMIN_TOOL_IMAGE  = "apache/polaris-admin-tool:" + POLARIS_VERSION;

    public static final String KEYCLOAK_IMAGE            = "quay.io/keycloak/keycloak:26.6.3";

    public static final String SPARK_IMAGE               = "ph1ll1phenry/spark_jars_cp:4.0.1";
    public static final String PYSPARK_NOTEBOOK_IMAGE    = "ph1ll1phenry/pyspark-notebook:x86_64-spark-4.0.1-vnc-clip-multi_pkg";

    public static final String JUPYTERHUB_HUB_IMAGE      = "jupyterhub/k8s-hub:3.3.8";
    public static final String JUPYTERHUB_PROXY_IMAGE    = "jupyterhub/configurable-http-proxy:4.6.1";

    public static final String GUACAMOLE_IMAGE           = "guacamole/guacamole:1.5.5";
    public static final String GUACD_IMAGE               = "guacamole/guacd:1.5.5";

    public static final String OAUTH2_PROXY_IMAGE        = "quay.io/oauth2-proxy/oauth2-proxy:v7.6.0";
    public static final String CURL_IMAGE                = "curlimages/curl:8.11.1";

    public static final String POSTGRES_ALPINE_IMAGE     = "postgres:16-alpine";

    /**
     * Overrides the appVersion of the vendored Trino Helm chart (see {@code
     * TrinoWorkloads#CHART_VERSION}, which pins the chart itself - a separate axis from the image
     * tag set here). The README sanity check expects {@code select version()} to report this.
     */
    public static final String TRINO_IMAGE_TAG           = "481";

    private DockerImages() {}

    private static String loadVersion(String versionKey) {
        Properties properties = new Properties();
        try (InputStream is = DockerImages.class.getResourceAsStream("/versions.properties")) {
            if (is == null) {
                throw new IllegalStateException("versions.properties missing from the classpath");
            }
            properties.load(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String version = properties.getProperty(versionKey);
        if (version == null || version.startsWith("${")) {
            throw new IllegalStateException(
                versionKey + " in versions.properties was not substituted by Maven resource filtering");
        }
        return version;
    }
}
