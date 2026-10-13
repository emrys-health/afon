package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformResource;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exposes services over HTTPS with the nginx ingress controller, and cert-manager issuing the certificates from
 * Let's Encrypt, on any cloud whose load balancer is provisioned for the nginx controller's service, ie Azure and GCP.
 * What differs per cloud is passed in: the controller's service annotations, while the DNS record pointing at the
 * {@link IngressV1} is the cloud's own to create.
 * <p>
 * The nginx controller and cert-manager are installed once, and any number of services can then be
 * {@link #expose exposed}.
 */
public class HttpsIngress {

    private final Construct scope;
    private final Release   nginxRelease;
    private final Release   certManager;

    /**
     * @param scope                  the construct holding the releases and ingresses
     * @param certificateDomainName  the domain name of the Let's Encrypt account, eg that of Polaris
     * @param nginxAnnotations       the annotations of the controller's service, eg for the load balancer's health
     *                               probe, or {@code null} for none
     */
    public HttpsIngress(Construct scope,
                                   HelmProvider helm,
                                   List<ITerraformDependable> dependsOn,
                                   String certificateDomainName,
                                   Map<String, String> nginxAnnotations) {
        this.scope        = scope;
        this.nginxRelease = CdkHelper.installNginxIngress(scope, helm, dependsOn, nginxAnnotations);
        this.certManager  = CdkHelper.installCertManager(
            scope, helm, dependsOn, certificateDomainName, List.of());
    }

    public Release nginxRelease() {
        return nginxRelease;
    }

    public Release certManager() {
        return certManager;
    }

    /**
     * Exposes the service over HTTPS at the domain name.
     *
     * @param tlsSecretName   the Kubernetes secret holding the TLS certificate
     * @param dependsOn       what the ingress must also wait for, eg the service it routes to
     * @param provisioners    the ingress' provisioners, eg to remove its finalizers on destroy
     * @param inboundCidrs    the source CIDRs the ingress admits, or empty to admit all
     * @return the ingress, whose load balancer address the DNS record must point at
     */
    public IngressV1 expose(String domainName,
                            String tlsSecretName,
                            String service,
                            int port,
                            String ingressName,
                            List<? extends TerraformResource> dependsOn,
                            Optional<List<LocalExecProvisioner>> provisioners,
                            List<String> inboundCidrs) {
        List<TerraformResource> dependencies = new ArrayList<>(List.of(nginxRelease, certManager));
        dependencies.addAll(dependsOn);
        return new CdkHelper(scope, domainName, tlsSecretName, port, service, ingressName)
            .createIngress(dependencies, provisioners, inboundCidrs);
    }
}
