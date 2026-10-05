package health.emrys.afon.cdk.gcp;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.google.data_google_dns_managed_zone.DataGoogleDnsManagedZone;
import com.hashicorp.cdktf.providers.google.data_google_dns_managed_zone.DataGoogleDnsManagedZoneConfig;
import com.hashicorp.cdktf.providers.google.dns_record_set.DnsRecordSet;
import com.hashicorp.cdktf.providers.google.dns_record_set.DnsRecordSetConfig;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import health.emrys.afon.cdk.CdkHelper;
import health.emrys.afon.cdk.PolarisConfig;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import software.constructs.Construct;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static health.emrys.afon.cdk.CdkHelper.POLARIS_PROVISIONERS;
import static health.emrys.afon.cdk.CdkHelper.POLARIS_INGRESS_NAME;

public class GcpPolarisStack extends GcpKubernetesStack {

    private static final Pattern DNS_ZONE_NAME_RULE = Pattern.compile("[a-z]([-a-z0-9]*[a-z0-9])?");
    private static final int     DNS_ZONE_NAME_MAX  = 63;
    private static final String POLARIS_INTERNAL = "polaris-internal";
    private static final String TLS_SECRET_NAME  = "polaris-tls";
    private static final int    POLARIS_PORT     = 8181;


    public GcpPolarisStack(Construct scope, StackId stackId, AfonPropertyProvider properties) {
        super(scope, stackId, properties);
        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", cloudProvider, properties);
        var util = new CdkHelper(
            this, gcpDomainName, TLS_SECRET_NAME, POLARIS_PORT
            , POLARIS_INTERNAL, POLARIS_INGRESS_NAME);
        HelmProvider               helm            = cloudProvider.configureHelm();
        List<ITerraformDependable> dependables     = cloudProvider.getDependables();
        Release                    nginxRelease    = CdkHelper.installNginxIngress(this, helm, dependables, null);
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


    /**
     * @return the name of the Cloud DNS managed zone of the domain name: the domain name with its dots replaced by
     * dashes, eg {@code emryspolarisgcp2-click} for {@code emryspolarisgcp2.click}.  An Azure DNS zone is named after
     * its domain name, but the name of a Cloud DNS zone cannot hold dots.  {@code scripts/gcp/create.sh} creates the
     * zone with the same name.
     * @throws IllegalArgumentException if the name is not a valid zone name, ie 1 to 63 lowercase letters, digits or
     * dashes, starting with a letter and not ending with a dash
     */
    static String dnsZoneName(String domainName) {
        String name = domainName.replace('.', '-');
        if (name.length() > DNS_ZONE_NAME_MAX || !DNS_ZONE_NAME_RULE.matcher(name).matches()) {
            throw new IllegalArgumentException(String.format(
                "The Cloud DNS zone name '%s', derived from the domain name '%s' by replacing its dots with dashes, "
                + "must be 1 to %d lowercase letters, digits or dashes, starting with a letter and not ending with a dash",
                name, domainName, DNS_ZONE_NAME_MAX));
        }
        return name;
    }

    private void configureDns(IngressV1 ingress) {
        DataGoogleDnsManagedZone zone = new DataGoogleDnsManagedZone(
            this,
            "dns-zone",
            DataGoogleDnsManagedZoneConfig
                .builder()
                .name(dnsZoneName(gcpDomainName))
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
        final App app = createApp();
        AfonPropertyProvider properties = new FilePropertyProvider();
        new GcpPolarisStack(app, StackId.of(GcpPolarisStack.class), properties);
        app.synth();
    }
}
