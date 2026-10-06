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
import org.slf4j.Logger;
import software.constructs.Construct;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static health.emrys.afon.cdk.CdkHelper.POLARIS_PROVISIONERS;
import static health.emrys.afon.cdk.CdkHelper.POLARIS_INGRESS_NAME;
import static health.emrys.afon.config.AfonProperty.GCP_PROJECT_ID;
import static health.emrys.afon.config.AfonProperty.GCP_SECRETS_ENABLED;
import static java.lang.String.format;

public class GcpPolarisStack extends GcpKubernetesStack {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(GcpPolarisStack.class);

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
        Release certManager = Boolean.parseBoolean(properties.getValue(GCP_SECRETS_ENABLED, "false"))
            ? util.installCertManager(this, helm, dependables,
                List.of(restoreCachedCertCommand(properties.getValue(GCP_PROJECT_ID), tlsSecretNames(gcpDomainName))))
            : util.installCertManager(this, helm, dependables);
        Service                    internalService = polarisConfig.deployPolaris(dependables);
        IngressV1 ingress = util.createIngress(
            List.of(
                nginxRelease,
                certManager,
                internalService),
            Optional.of(POLARIS_PROVISIONERS));
        configureDns(ingress);
    }

    static String[] tlsSecretNames(String domainName) {
        String kebabDomainName = domainName.replace(".", "-");
        return new String[]{kebabDomainName + "-tls-crt", kebabDomainName + "-tls-key"};
    }

    /**
     * Restores the Polaris TLS secrets from Secret Manager before cert-manager's ingress-shim gets a
     * chance to request a fresh cert from Let's Encrypt.  Attached as an extra create-time command
     * on the cert-manager Helm release, so it runs (and completes) before the dependent Ingress is
     * even created.  The cache is populated externally, ahead of deploy, by
     * {@code scripts/gcp/issue-cert.sh} (or {@code scripts/gcp/secrets.sh store}).  A missing or empty
     * secret is not an error, cert-manager simply issues a new cert via its HTTP-01 ClusterIssuer as a fallback.
     */
    static String restoreCachedCertCommand(String projectId, String[] secretNames) {
        logger.info("Restoring cached secrets {} from Secret Manager of project {}", secretNames, projectId);
        return format(
            """
            CRT_B64=$(gcloud secrets versions access latest --secret='%2$s' --project='%1$s' 2>/dev/null)
            KEY_B64=$(gcloud secrets versions access latest --secret='%3$s' --project='%1$s' 2>/dev/null)
            if [ -n "$CRT_B64" ] && [ -n "$KEY_B64" ]; then
              kubectl create secret generic %4$s \
                --type=kubernetes.io/tls \
                --from-literal=tls.crt="$(echo "$CRT_B64" | base64 -d)" \
                --from-literal=tls.key="$(echo "$KEY_B64" | base64 -d)" \
                --dry-run=client -o yaml | kubectl apply -f -
            fi
            """, projectId, secretNames[0], secretNames[1], TLS_SECRET_NAME);
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
