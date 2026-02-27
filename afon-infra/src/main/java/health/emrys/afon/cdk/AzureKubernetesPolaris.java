package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_dns_zone.DataAzurermDnsZone;
import com.hashicorp.cdktf.providers.azurerm.data_azurerm_dns_zone.DataAzurermDnsZoneConfig;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecord;
import com.hashicorp.cdktf.providers.azurerm.dns_a_record.DnsARecordConfig;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import software.constructs.Construct;

import java.util.List;

public class AzureKubernetesPolaris extends AzureKubernetesStack {

    private static final String DOMAIN_NAME = "emryspolarisazure.click";

    public AzureKubernetesPolaris(Construct scope, String id) {
        super(scope, id);
        PolarisConfig polarisConfig  = new PolarisConfig(this, "polaris", cloudProvider);
        Service       polarisService = polarisConfig.deployPolaris(cloudProvider.getDependables());
        configureDns(polarisService);
    }

    private static final String DNS_RESOURCE_GROUP = "afon_resource_group";

    private void configureDns(Service polarisService) {
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
                        polarisService.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getIp()
                ))
                .build());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesPolaris(app, ID);
        app.synth();
    }
}
