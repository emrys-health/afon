package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformResource;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_dns_zone.DataAzurermDnsZone;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecord;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecordConfig;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1Metadata;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1Spec;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1SpecRule;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1SpecRuleHttp;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1SpecRuleHttpPath;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1SpecRuleHttpPathBackend;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1SpecRuleHttpPathBackendService;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1SpecRuleHttpPathBackendServicePort;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1SpecTls;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpec;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static health.emrys.afon.infra.Defaults.POLARIS_SERVICE_NAME;

public class AzureKubernetesPolaris extends AzureKubernetesStack {

    private static final String DOMAIN_NAME            = "emryspolarisazure.click";
    private static final String DNS_RESOURCE_GROUP = "afon_resource_group";
    private static final String POLARIS_INTERNAL   = "polaris-internal";
    private static final String TLS_SECRET_NAME    = "polaris-tls";
    private static final int    POLARIS_PORT           = 8181;

    public AzureKubernetesPolaris(Construct scope, String id) {
        super(scope, id);
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
        Release  nginxRelease       = cloudProvider.installNginxIngress();
        Release  certManager        = cloudProvider.installCertManager();
        Service  internalService    = polarisConfig.deployPolaris(cloudProvider.getDependables());
        IngressV1 ingress           = createIngress(List.of(nginxRelease, certManager, internalService));
        configureDns(ingress);
    }

    private IngressV1 createIngress(List<TerraformResource> dependencies) {
        IngressV1SpecRuleHttpPathBackendServicePort port = IngressV1SpecRuleHttpPathBackendServicePort.builder()
                                                                                                       .number(POLARIS_PORT)
                                                                                                       .build();
        IngressV1SpecRuleHttpPathBackendService backend = IngressV1SpecRuleHttpPathBackendService.builder()
                                                                                                 .name(POLARIS_INTERNAL)
                                                                                                 .port(port)
                                                                                                 .build();
        IngressV1SpecRuleHttpPath httpPath = IngressV1SpecRuleHttpPath.builder()
                                                                      .path("/")
                                                                      .pathType("Prefix")
                                                                      .backend(IngressV1SpecRuleHttpPathBackend.builder()
                                                                                                             .service(backend)
                                                                                                             .build())
                                                                      .build();
        IngressV1SpecRule ingressV1SpecRule = IngressV1SpecRule.builder()
                                                               .host(DOMAIN_NAME)
                                                               .http(IngressV1SpecRuleHttp.builder()
                                                                               .path(List.of(httpPath))
                                                                               .build())
                                                               .build();

        IngressV1Spec nginxSpec = IngressV1Spec.builder()
                                           .ingressClassName("nginx")
                                           .tls(List.of(
                                               IngressV1SpecTls.builder()
                                                               .hosts(List.of(DOMAIN_NAME))
                                                               .secretName(TLS_SECRET_NAME)
                                                               .build()
                                                       ))
                                           .rule(List.of(
                                               ingressV1SpecRule
                                                        ))
                                           .build();
        return IngressV1.Builder.create(this, POLARIS_SERVICE_NAME)
                                .metadata(IngressV1Metadata.builder()
                                                           .name(POLARIS_SERVICE_NAME)
                                                           .annotations(Map.of(
                                                                   "cert-manager.io/cluster-issuer", "letsencrypt-prod"
                                                           ))
                                                           .build())
                                .dependsOn(dependencies)
                                .provisioners(uponDestroyRun(PolarisConfig.NO_POLARIS_FINALIZERS))
                                .spec(nginxSpec)
                                .waitForLoadBalancer(true)
                                .build();
    }

    private void configureDns(IngressV1 ingress) {
        DataAzurermDnsZone dnsZone = DataAzurermDnsZone.Builder.create(this, "dns-zone")
                        .name(DOMAIN_NAME)
                        .resourceGroupName(DNS_RESOURCE_GROUP)
                        .build();

        new DnsARecord(this, "polaris-dns", DnsARecordConfig.builder()
                .name("@")
                .zoneName(dnsZone.getName())
                .resourceGroupName(DNS_RESOURCE_GROUP)
                .ttl(300)
                .records(List.of(
                        ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getIp()
                ))
                .build());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesPolaris(app, ID);
        app.synth();
    }
}
