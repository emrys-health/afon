package health.emrys.afon.cdk.gcp;

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
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.cdk.TFUtils;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static health.emrys.afon.cdk.TFUtils.POLARIS_PROVISIONERS;
import static health.emrys.afon.cdk.TFUtils.clusterIpService;
import static health.emrys.afon.infra.Defaults.POLARIS_INGRESS_NAME;

public class GcpKubernetesPolaris extends GcpKubernetesStack {

    private static final String DNS_ZONE_NAME    = "polaris-zone";
    private static final String POLARIS_INTERNAL = "polaris-internal";
    private static final String TLS_SECRET_NAME  = "polaris-tls";
    private static final int    POLARIS_PORT     = 8181;


    public GcpKubernetesPolaris(Construct scope, String id, Properties properties) {
        super(scope, id, properties);
        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider);
        var util = new TFUtils(
            this, gcpDomainName, TLS_SECRET_NAME, POLARIS_PORT
            , POLARIS_INTERNAL, POLARIS_INGRESS_NAME);
        HelmProvider               helm            = cloudProvider.configureHelm();
        List<ITerraformDependable> dependables     = cloudProvider.getDependables();
        Release                    nginxRelease    = TFUtils.installNginxIngress(this, helm, dependables, null);
        Release                    certManager     = util.installCertManager(this, helm, dependables);
        Service                    internalService = polarisConfig.deployPolaris(dependables);
        IngressV1 ingress = util.createIngress(
            List.of(
                nginxRelease,
                certManager,
                internalService),
            Optional.of(POLARIS_PROVISIONERS));
        configureDns(ingress);
    }


    private void configureDns(IngressV1 ingress) {
        DataGoogleDnsManagedZone zone = new DataGoogleDnsManagedZone(
            this,
            "dns-zone",
            DataGoogleDnsManagedZoneConfig
                .builder()
                .name(DNS_ZONE_NAME)
                .build());

        String ingressIp = ingress.getStatus().get(0).getLoadBalancer().get(0).getIngress().get(0).getIp();
        new DnsRecordSet(
            this,
            "polaris-dns",
            DnsRecordSetConfig
                .builder()
                .name(gcpDomainName + ".")
                .type("A")
                .ttl(300)
                .managedZone(zone.getName())
                .rrdatas(List.of(ingressIp))
                .build());
    }

    public static void main(String[] args) {
        final App  app        = new App();
        Properties properties = new AfonPropertiesLoader().getProperties();
        new GcpKubernetesPolaris(app, ID.toString(), properties);
        app.synth();
    }
}
