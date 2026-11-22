package health.emrys.afon.cdk.configs;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.config_map.ConfigMapMetadata;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerPort;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerReadinessProbe;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerReadinessProbeHttpGet;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerVolumeMount;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolume;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecVolumeConfigMap;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.cdk.CdkUtils;
import health.emrys.afon.cdk.DockerImageResolver;
import health.emrys.afon.config.AfonCatalog;
import software.constructs.Construct;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The proxy that stands between Trino and the Data Providers' catalogs that a project's TRE reads.
 *
 * <p>Trino's Iceberg client calls {@code GET /v1/config} of a catalog's Polaris, once and before any query, with the static authentication of the
 * catalog, and only calls Polaris with the token of the member who queries after that.  A Data Provider's Polaris takes nothing but a member's
 * token (its audience, its second factor, its project), so it refuses what the catalog holds.  The proxy answers that one call itself, with
 * what Polaris would say of the catalog, its prefix, which is fixed, and passes every other request on to the Data Provider's Polaris, with the
 * member's own token as the client sent it.  So there is no credential of the TRE's that the Data Provider must accept, and nothing weakened at it.
 *
 * <p>Trino reaches it over plain HTTP, inside the cluster, so its pods are the only ones that should: no pod but Trino's has it as a peer
 * that it may reach, as the notebook pods' network policy lists what they may.
 *
 * <p>Each catalog is served at a path of its own, its name in Trino, eg {@code http://trino-catalog-proxy:8080/lon_cvd}.
 */
public final class TrinoCatalogProxy {

    public static final String SERVICE_NAME = "trino-catalog-proxy";
    public static final int    PORT         = 8080;
    static final Map<String, String> LABELS = Map.of("app", SERVICE_NAME);

    private static final String CONFIG_MAP_NAME = SERVICE_NAME + "-config";
    private static final String CONFIG_DIR      = "/etc/afon-nginx";
    private static final String CONFIG_FILE     = "nginx.conf";
    private static final String HEALTH_PATH     = "/healthz";
    private static final String CA_BUNDLE       = "/etc/ssl/certs/ca-certificates.crt";

    /** What may be written into the configuration of the proxy: the names of catalogs and hosts. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.-]+");

    private TrinoCatalogProxy() {
    }

    /**
     * @param trinoName the name of the catalog in Trino, see {@link TrinoWorkloads#dataProviderCatalogName}
     * @return the endpoint of the catalog for Trino, which the proxy serves
     */
    public static String uriOf(String trinoName) {
        return "http://" + SERVICE_NAME + ":" + PORT + "/" + trinoName;
    }

    /**
     * @param catalogs the catalogs that Data Providers serve
     * @return the configuration of the proxy: for each catalog, the answer to the call of Trino's client to configure it, and the rest passed to the
     * Data Provider's endpoint
     */
    static String nginxConf(List<AfonCatalog> catalogs) {
        StringBuilder conf = new StringBuilder()
            .append("worker_processes 1;\n")
            .append("pid /tmp/nginx.pid;\n")
            .append("error_log /dev/stderr info;\n")
            .append("events { worker_connections 256; }\n")
            .append("http {\n")
            .append("  access_log /dev/stdout;\n")
            .append("  client_body_temp_path /tmp/client_temp;\n")
            .append("  proxy_temp_path /tmp/proxy_temp;\n")
            .append("  fastcgi_temp_path /tmp/fastcgi_temp;\n")
            .append("  uwsgi_temp_path /tmp/uwsgi_temp;\n")
            .append("  scgi_temp_path /tmp/scgi_temp;\n")
            .append("  server {\n")
            .append("    listen ").append(PORT).append(";\n")
            .append("    location = ").append(HEALTH_PATH).append(" { return 200 \"ok\\n\"; }\n");
        for (AfonCatalog catalog : catalogs) {
            String trinoName = TrinoWorkloads.dataProviderCatalogName(catalog);
            URI endpoint = URI.create(catalog.catalogUri());
            String host = endpoint.getHost();
            String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/+$", "");
            if (!"https".equals(endpoint.getScheme()) || host == null) {
                throw new IllegalArgumentException("The endpoint of the Data Provider catalog '" + catalog.id() + "' is not an https URL with a host: "
                                                   + catalog.catalogUri());
            }
            String authority = endpoint.getPort() == -1 ? host : host + ":" + endpoint.getPort();
            for (String value : List.of(trinoName, catalog.name(), host, path.replace("/", ""))) {
                if (!value.isEmpty() && !SAFE.matcher(value).matches()) {
                    throw new IllegalArgumentException("'" + value + "', of the Data Provider catalog '" + catalog.id() + "', cannot be in the proxy's configuration");
                }
            }
            conf.append("    # ").append(trinoName).append(": the catalog ").append(catalog.name()).append(" at ").append(catalog.catalogUri()).append("\n")
                .append("    location = /").append(trinoName).append("/v1/config {\n")
                .append("      default_type application/json;\n")
                .append("      return 200 '{\"defaults\":{},\"overrides\":{\"prefix\":\"").append(catalog.name()).append("\"}}';\n")
                .append("    }\n")
                .append("    location /").append(trinoName).append("/ {\n")
                .append("      proxy_http_version 1.1;\n")
                .append("      proxy_set_header Host ").append(host).append(";\n")
                .append("      proxy_ssl_server_name on;\n")
                .append("      proxy_ssl_name ").append(host).append(";\n")
                .append("      proxy_ssl_verify on;\n")
                .append("      proxy_ssl_verify_depth 5;\n")   // its default, 1, refuses any chain with an intermediate
                .append("      proxy_ssl_trusted_certificate ").append(CA_BUNDLE).append(";\n")
                .append("      proxy_pass https://").append(authority).append(path).append("/;\n")
                .append("    }\n");
        }
        return conf.append("  }\n}\n").toString();
    }

    /**
     * Deploys the proxy, if there are catalogs for it, as a deployment, its configuration and its service, in the namespace of Trino.
     */
    public static void deploy(Construct scope, List<? extends ITerraformDependable> dependsOn, DockerImageResolver images, List<AfonCatalog> catalogs) {
        if (catalogs.isEmpty()) {
            return;
        }
        ConfigMap configMap = ConfigMap.Builder
            .create(scope, "trinoCatalogProxyConfig")
            .metadata(ConfigMapMetadata.builder().name(CONFIG_MAP_NAME).namespace(TrinoWorkloads.TRINO_NAMESPACE).build())
            .data(Map.of(CONFIG_FILE, nginxConf(catalogs)))
            .dependsOn(new ArrayList<>(dependsOn))
            .build();
        List<ITerraformDependable> deps = new ArrayList<>(dependsOn);
        deps.add(configMap);

        DeploymentSpecTemplateSpecContainer container = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("nginx")
            .image(images.nginx())
            .args(List.of("nginx", "-c", CONFIG_DIR + "/" + CONFIG_FILE, "-g", "daemon off;"))
            .port(List.of(DeploymentSpecTemplateSpecContainerPort.builder().containerPort(PORT).protocol("TCP").build()))
            .volumeMount(List.of(DeploymentSpecTemplateSpecContainerVolumeMount.builder().name("config").mountPath(CONFIG_DIR).readOnly(true).build()))
            .readinessProbe(DeploymentSpecTemplateSpecContainerReadinessProbe
                                .builder()
                                .httpGet(DeploymentSpecTemplateSpecContainerReadinessProbeHttpGet.builder().path(HEALTH_PATH).port(String.valueOf(PORT)).build())
                                .initialDelaySeconds(2)
                                .periodSeconds(10)
                                .build())
            .build();
        DeploymentSpecTemplateSpecVolume volume = DeploymentSpecTemplateSpecVolume
            .builder()
            .name("config")
            .configMap(DeploymentSpecTemplateSpecVolumeConfigMap.builder().name(CONFIG_MAP_NAME).build())
            .build();
        CdkUtils.deploy(scope, deps, LABELS, List.of(container), List.of(volume), SERVICE_NAME);

        Service.Builder.create(scope, "trinoCatalogProxyService")
                       .metadata(ServiceMetadata.builder().name(SERVICE_NAME).namespace(TrinoWorkloads.TRINO_NAMESPACE).build())
                       .dependsOn(deps)
                       .spec(ServiceSpec.builder()
                                        .selector(LABELS)
                                        .type("ClusterIP")
                                        .port(List.of(ServiceSpecPort.builder().name("http").port(PORT).targetPort(String.valueOf(PORT)).protocol("TCP").build()))
                                        .build())
                       .build();
    }
}
