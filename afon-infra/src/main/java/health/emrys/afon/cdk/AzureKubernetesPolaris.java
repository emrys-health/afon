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
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static health.emrys.afon.cdk.TFUtils.POLARIS_PROVISIONERS;
import static health.emrys.afon.infra.Defaults.JUPYTER_SERVICE_NAME;
import static health.emrys.afon.infra.Defaults.POLARIS_INGRESS_NAME;

public class AzureKubernetesPolaris extends AzureKubernetesStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AzureKubernetesStack.class);

    private static final String  POLARIS_INTERNAL = "polaris-internal";
    private static final String  TLS_SECRET_NAME  = "polaris-tls";
    private static final int     POLARIS_PORT     = 8181;
    private final        String  resourceGroup;
    protected final      Release nginxRelease;
    protected final      Release certManager;
    private final        Service internalService;

    public AzureKubernetesPolaris(Construct scope, String id,
                                  Properties properties) {
        super(scope, id, properties);
        resourceGroup = AfonProperties.AZURE_RESOURCE_GROUP.getValueFrom(properties);

        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider) {
            @Override
            protected Service configurePolarisService(Deployment polarisDeployment, Map<String, String> polarisLabels) {
                return Service.Builder
                    .create(AzureKubernetesPolaris.this, POLARIS_INTERNAL)
                    .metadata(ServiceMetadata
                                  .builder()
                                  .name(POLARIS_INTERNAL)
                                  .build())
                    .dependsOn(List.of(polarisDeployment))
                    .spec(ServiceSpec
                              .builder()
                              .selector(polarisLabels)
                              .type("ClusterIP")
                              .port(List.of(PolarisConfig.POLARIS_PORTS))
                              .build())
                    .build();
            }
        };
        var util = new TFUtils(
            this, azurePolarisDomainName, TLS_SECRET_NAME,
            POLARIS_PORT, POLARIS_INTERNAL, POLARIS_INGRESS_NAME);
        HelmProvider               helm        = cloudProvider.configureHelm();
        List<ITerraformDependable> dependables = cloudProvider.getDependables();
        nginxRelease    = configureNginx(helm, dependables);
        certManager     = util.installCertManager(this, helm, dependables);
        internalService = polarisConfig.deployPolaris(cloudProvider.getDependables());
        IngressV1 ingress = util.createIngress(
            List.of(
                nginxRelease, certManager, internalService),
            Optional.of(POLARIS_PROVISIONERS));
        configureDns(ingress, azurePolarisDomainName);
    }

    private @NonNull Release configureNginx(HelmProvider helm, List<ITerraformDependable> dependables) {
        final Release nginxRelease;
        Map<String, String> annotations = Map.of(
            "controller.service.annotations.service\\.beta\\" +
            ".kubernetes\\.io/azure-load-balancer-health-probe" +
            "-request-path", "/healthz");
        nginxRelease = TFUtils.installNginxIngress(this, helm, dependables, annotations);
        return nginxRelease;
    }

    protected void configureDns(IngressV1 ingress, String domainName) {
        String suffix = domainName.replace(".", "-");
        DataAzurermDnsZone dnsZone = DataAzurermDnsZone.Builder
            .create(this, "dns-zone-" + suffix)
            .name(domainName)
            .resourceGroupName(resourceGroup)
            .build();

        List<String> ipAddresses = List.of(
            ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getIp()
                                          );
        new DnsARecord(
            this, "dns-" + suffix,
            DnsARecordConfig
                .builder()
                .name("@")
                .zoneName(dnsZone.getName())
                .resourceGroupName(resourceGroup)
                .ttl(300)
                .records(ipAddresses)
                .build()
        );
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesPolaris(app, ID.toString(), new AfonPropertiesLoader().getProperties());
        app.synth();
    }
}
