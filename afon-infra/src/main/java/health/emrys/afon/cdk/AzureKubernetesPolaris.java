package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_dns_zone.DataAzurermDnsZone;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecord;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecordConfig;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import health.emrys.afon.config.AfonPropertiesLoader;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.AZURE_RESOURCE_GROUP;

public class AzureKubernetesPolaris extends AzureKubernetesStack {

    public  static final String AZURE_DOMAIN_NAME  = "emryspolarisazure.click";
    private static final String POLARIS_INTERNAL   = "polaris-internal";
    private static final String TLS_SECRET_NAME    = "polaris-tls";
    private static final int    POLARIS_PORT       = 8181;

    public AzureKubernetesPolaris(Construct scope, String id,
                                  Properties properties) {
        super(scope, id, properties);

        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider) {
            @Override
            protected Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
                return Service.Builder.create(AzureKubernetesPolaris.this, POLARIS_INTERNAL)
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
        var                        util         = new TFUtils(this, AZURE_DOMAIN_NAME, TLS_SECRET_NAME, POLARIS_PORT, POLARIS_INTERNAL);
        HelmProvider               helm         = cloudProvider.configureHelm();
        List<ITerraformDependable> dependables  = cloudProvider.getDependables();
        Map<String, String>        annotations  = Map.of("controller.service.annotations.service\\.beta\\.kubernetes\\.io/azure-load-balancer-health-probe-request-path", "/healthz");
        Release                    nginxRelease = util.installNginxIngress(this, helm, dependables, annotations);
        Release                    certManager  = util.installCertManager(this, helm, dependables);
        Service   internalService = polarisConfig.deployPolaris(cloudProvider.getDependables());
        IngressV1 ingress         = util.createIngress(List.of(nginxRelease, certManager, internalService));
        configureDns(ingress);
    }

    private void configureDns(IngressV1 ingress) {
        DataAzurermDnsZone dnsZone = DataAzurermDnsZone.Builder.create(this, "dns-zone")
                                                               .name(AZURE_DOMAIN_NAME)
                                                               .resourceGroupName(cloudProvider.getResourceGroup())
                                                               .build();

        new DnsARecord(this, "polaris-dns", DnsARecordConfig.builder()
                                                            .name("@")
                                                            .zoneName(dnsZone.getName())
                                                            .resourceGroupName(cloudProvider.getResourceGroup())
                                                            .ttl(300)
                                                            .records(List.of(
                                                                ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getIp()
                                                                            ))
                                                            .build());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesPolaris(app, ID.toString(), new AfonPropertiesLoader().getProperties());
        app.synth();
    }
}
