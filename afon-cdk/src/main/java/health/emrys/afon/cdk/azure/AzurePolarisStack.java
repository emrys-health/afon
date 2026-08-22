package health.emrys.afon.cdk.azure;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.AppConfig;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_dns_zone.DataAzurermDnsZone;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecord;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecordConfig;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static health.emrys.afon.cdk.CdkHelper.POLARIS_PROVISIONERS;
import static health.emrys.afon.cluster.Defaults.POLARIS_INGRESS_NAME;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.ConfigUtils.cdktfDir;

public class AzurePolarisStack extends AzureKubernetesStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(AzurePolarisStack.class);

    private static final String  POLARIS_INTERNAL = "polaris-internal";
    private static final String  TLS_SECRET_NAME  = "polaris-tls";
    private static final int     POLARIS_PORT     = 8181;
    protected final      Release nginxRelease;
    protected final      Release certManager;
    private final        String  resourceGroup;
    private final        Service internalService;

    public AzurePolarisStack(Construct scope, AfonCloud id,
                             AfonPropertyProvider properties) {
        super(scope, id, properties);
        logger.info("Using local backend {}", getLocalBackendPath());
        resourceGroup = properties.getValue(AfonProperty.AZURE_RESOURCE_GROUP);

        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider, properties);
        var helper = new CdkHelper(
            this, azurePolarisDomainName, TLS_SECRET_NAME,
            POLARIS_PORT, POLARIS_INTERNAL, POLARIS_INGRESS_NAME);
        HelmProvider               helm        = cloudProvider.configureHelm();
        List<ITerraformDependable> dependables = cloudProvider.getDependables();
        nginxRelease    = configureNginx(helm, dependables);
        certManager     = helper.installCertManager(this, helm, dependables);
        internalService = polarisConfig.deployPolaris(cloudProvider.getDependables());
        IngressV1 ingress = helper.createIngress(
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
        nginxRelease = CdkHelper.installNginxIngress(this, helm, dependables, annotations);
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
        final App app = new App(AppConfig.builder()
                .outdir(cdktfDir())
                .build());
        new AzurePolarisStack(app, AZURE, new FilePropertyProvider());
        app.synth();
    }
}
