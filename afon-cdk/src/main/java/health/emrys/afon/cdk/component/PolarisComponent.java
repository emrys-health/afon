package health.emrys.afon.cdk.component;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import health.emrys.afon.cdk.CloudPlatform;
import health.emrys.afon.cdk.HealthCheck;
import health.emrys.afon.cdk.HttpsRoute;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonPropertyProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static health.emrys.afon.cdk.CdkHelper.POLARIS_INGRESS_NAME;
import static health.emrys.afon.cdk.CdkHelper.POLARIS_PROVISIONERS;
import static health.emrys.afon.polaris.PolarisConstants.POLARIS_INTERNAL_SERVICE_NAME;

/**
 * Polaris, the Iceberg catalog, behind its HTTPS ingress and DNS record at the cloud's {@code POLARIS_DOMAIN_NAME}.
 * It waits for the KeyCloak of the stack, if it has one, as it does OIDC discovery on startup.
 */
public class PolarisComponent implements StackComponent {

    private static final int    POLARIS_PORT            = 8181;
    private static final String POLARIS_TLS_SECRET_NAME = "polaris-tls";

    private String domainName;

    @Override
    public void deploy(StackContext context) {
        CloudPlatform        platform   = context.platform();
        AfonPropertyProvider properties = context.properties();
        domainName = properties.getCloudValue(platform.cloud(), "POLARIS_DOMAIN_NAME");
        if (platform.routesToNodePorts()) {
            deployBehindNodePort(context);
        } else {
            deployBehindClusterIp(context);
        }
    }

    /**
     * @return the domain name Polaris is served at
     */
    public String domainName() {
        return domainName;
    }

    private HttpsRoute route(List<Service> services) {
        return new HttpsRoute(
            "polaris", domainName, POLARIS_INTERNAL_SERVICE_NAME, POLARIS_PORT, POLARIS_INGRESS_NAME,
            new HealthCheck(PolarisConfig.HEALTH_READY_PATH, PolarisConfig.MANAGEMENT_PORT_NAME),
            POLARIS_TLS_SECRET_NAME, true, services, Optional.of(POLARIS_PROVISIONERS));
    }

    /**
     * For a platform whose load balancer routes to node ports: the ingress is exposed first, and the NodePort service
     * is the stack's own.  The ids of the resources are in the tofu state of the deployed stacks, so cannot be
     * named alike those of {@link #deployBehindClusterIp}.
     */
    private void deployBehindNodePort(StackContext context) {
        CloudPlatform platform = context.platform();
        platform.exposeHttps(route(List.of()));
        List<ITerraformDependable> polarisDependables = new ArrayList<>(platform.getDependables());
        // Polaris does OIDC discovery against the KeyCloak hostname, so must be ordered after its DNS record
        context.find(KeycloakComponent.class).ifPresent(keycloak -> polarisDependables.add(keycloak.dnsRecord()));
        PolarisConfig polarisConfig = new PolarisConfig(context.stack(), "polaris", platform, context.properties()) {
            @Override
            protected Service configurePolarisService(Deployment polarisDeployment) {
                return null; // the service is the stack's own
            }
        };
        polarisConfig.deployPolaris(polarisDependables);
        Service.Builder
            .create(context.stack(), POLARIS_INTERNAL_SERVICE_NAME)
            .metadata(ServiceMetadata
                          .builder()
                          .name(POLARIS_INTERNAL_SERVICE_NAME)
                          .namespace("default")
                          .build())
            .dependsOn(platform.getDependables())
            .spec(ServiceSpec
                      .builder()
                      .selector(polarisConfig.getPolarisLabels()) // Selects the Pods created by the Polaris deployment
                      .type("NodePort")
                      .internalTrafficPolicy("Cluster")
                      .clusterIps(List.of())
                      // The management port is exposed too, so the load balancer can reach the health checks.
                      .port(List.of(polarisConfig.getPolarisSpecPort(), polarisConfig.getPolarisManagementSpecPort()))
                      .build())
            .build();
    }

    /**
     * For a platform whose ingress routes to a ClusterIP service, which Polaris creates, so the ingress is exposed
     * once it exists.
     */
    private void deployBehindClusterIp(StackContext context) {
        CloudPlatform platform = context.platform();
        PolarisConfig polarisConfig = new PolarisConfig(context.stack(), "polaris", platform, context.properties());
        Service internalService = polarisConfig.deployPolaris(platform.getDependables());
        platform.exposeHttps(route(List.of(internalService)));
    }
}
