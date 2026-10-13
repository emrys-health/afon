package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.TerraformResource;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A cloud provider exposing services over HTTPS with the nginx ingress controller and cert-manager, see
 * {@link HttpsIngress}, ie Azure and GCP.  The first route exposed installs them, and what differs per
 * cloud is the controller's annotations, the cached certificate and the DNS record.
 */
public abstract class AbstractHttpsCloudProvider extends AbstractCloudProvider {

    private HttpsIngress nginx;

    public AbstractHttpsCloudProvider(@NonNull Construct scope, @NonNull String id) {
        super(scope, id);
    }

    @Override
    public synchronized HttpsExposure exposeHttps(HttpsRoute route) {
        if (nginx == null) {
            // the Let's Encrypt account is that of the first route
            nginx = new HttpsIngress(stack(), configureHelm(), getDependables(), route.domainName(), nginxAnnotations());
        }
        List<TerraformResource> dependsOn = new ArrayList<>(route.dependsOn());
        if (route.cacheCertificate()) {
            // restored before the ingress is created, else cert-manager's ingress-shim would issue a new certificate
            restoreCachedCertCommand(route).ifPresent(command -> dependsOn.add(
                Resource.Builder.create(stack(), "restoreCachedCert-" + route.component())
                                .dependsOn(List.of(nginx.certManager()))
                                .provisioners(CdkUtils.uponCreateRun(command))
                                .build()));
        }
        IngressV1 ingress = nginx.expose(
            route.domainName(), route.tlsSecretName(), route.service(), route.port(), route.ingressName(),
            dependsOn, route.ingressProvisioners());
        return new HttpsExposure(ingress, configureDns(ingress, route));
    }

    /**
     * The nginx ingress controller runs in the cluster.
     */
    @Override
    public Map<String, String> ingressPodLabels() {
        return CdkHelper.NGINX_INGRESS_POD_LABELS;
    }

    /**
     * @return the nginx ingress controller's release, once the first route is exposed, which a workload that needs the
     * cluster's data plane to be serving, eg Spark on Azure, can wait for
     */
    public synchronized Release nginxRelease() {
        if (nginx == null) {
            throw new IllegalStateException("The nginx ingress controller is installed when the first route is exposed");
        }
        return nginx.nginxRelease();
    }

    /**
     * @return the annotations of the controller's service, eg for its load balancer's health probe, or null for none
     */
    protected abstract Map<String, String> nginxAnnotations();

    /**
     * @return the command restoring the route's cached certificate, or empty if the cloud has none configured
     */
    protected abstract Optional<String> restoreCachedCertCommand(HttpsRoute route);

    /**
     * @return the DNS record of the route's domain name, pointing at the ingress' load balancer
     */
    protected abstract ITerraformDependable configureDns(IngressV1 ingress, HttpsRoute route);
}
