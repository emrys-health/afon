package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.google.data_google_dns_managed_zone.DataGoogleDnsManagedZone;
import com.hashicorp.cdktf.providers.google.data_google_dns_managed_zone.DataGoogleDnsManagedZoneConfig;
import com.hashicorp.cdktf.providers.google.dns_record_set.DnsRecordSet;
import com.hashicorp.cdktf.providers.google.dns_record_set.DnsRecordSetConfig;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class GcpKubernetesPolaris extends GcpKubernetesStack {

    private static final String DOMAIN_NAME      = "emryspolarisgcp.click";
    private static final String DNS_ZONE_NAME    = "polaris-zone";
    private static final String POLARIS_INTERNAL = "polaris-internal";
    private static final String TLS_SECRET_NAME  = "polaris-tls";
    private static final int    POLARIS_PORT     = 8181;

    public GcpKubernetesPolaris(Construct scope, String id) {
        super(scope, id);
        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider) {
            @Override
            protected Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
                return Service.Builder
                    .create(GcpKubernetesPolaris.this, POLARIS_INTERNAL)
                    .metadata(ServiceMetadata.builder()
                                             .name(POLARIS_INTERNAL)
                                             .build())
                    .dependsOn(List.of(polarisDeployment))
                    .spec(ServiceSpec.builder()
                                     .selector(polarisLabels)
                                     .type("ClusterIP")
                                     .port(List.of(PolarisConfig.POLARIS_PORTS))
                                     .build())
                    .build();
            }
        };
        var                        util            = new TFUtils(this, DOMAIN_NAME, TLS_SECRET_NAME, POLARIS_PORT, POLARIS_INTERNAL);
        HelmProvider               helm            = cloudProvider.configureHelm();
        List<ITerraformDependable> dependables     = cloudProvider.getDependables();
        Release                    nginxRelease    = util.installNginxIngress(this, helm, dependables, null);
        Release                    certManager     = util.installCertManager(this, helm, dependables);
        Service                    internalService = polarisConfig.deployPolaris(dependables);
        IngressV1                  ingress         = util.createIngress(List.of(nginxRelease, certManager, internalService));
        configureDns(ingress);
    }


    private void configureDns(IngressV1 ingress) {
        DataGoogleDnsManagedZone zone = new DataGoogleDnsManagedZone(this, "dns-zone",
                                                                     DataGoogleDnsManagedZoneConfig.builder()
                                                                                                   .name(DNS_ZONE_NAME)
                                                                                                   .build());

        new DnsRecordSet(this, "polaris-dns", DnsRecordSetConfig.builder()
                                                                .name(DOMAIN_NAME + ".")
                                                                .type("A")
                                                                .ttl(300)
                                                                .managedZone(zone.getName())
                                                                .rrdatas(List.of(
                                                                    ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getIp()
                                                                                ))
                                                                .build());
    }

    public static void main(String[] args) {
        final App app = new App();
        new GcpKubernetesPolaris(app, ID);
        app.synth();
    }
}
