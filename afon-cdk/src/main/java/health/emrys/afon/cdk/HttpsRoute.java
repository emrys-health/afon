package health.emrys.afon.cdk;

import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformResource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a component asks of its {@link CloudPlatform} to be reachable over HTTPS: the platform decides how, eg via an
 * ALB and a Route 53 record on AWS, or via the nginx ingress controller and a DNS record on Azure and GCP.
 *
 * @param component        the component, eg {@code polaris} or {@code jupyter}, whose cloud-specific config the platform
 *                         reads by the convention {@code <CLOUD>_<COMPONENT>_<NAME>}, eg {@code AWS_POLARIS_CERT_ARN}
 * @param domainName       the domain name the component is served at
 * @param service          the Kubernetes service routed to
 * @param port             the port of the service
 * @param ingressName      the name of the Kubernetes ingress
 * @param healthCheck      where the load balancer probes the service's pods, for platforms with a load balancer of their own, eg
 *                         the AWS ALB, ignored by those that route through the ingress controller
 * @param tlsSecretName    the Kubernetes secret holding the TLS certificate, for platforms terminating TLS in the
 *                         cluster, ignored by those terminating it at their load balancer
 * @param cacheCertificate whether the certificate is cached in the cloud's secret store, to be restored rather than
 *                         issued anew, for platforms issuing them in the cluster.  Only the first route exposed on a
 *                         platform can be cached, as the platform installs what restores it only once
 * @param dependsOn        what the ingress must wait for, eg the service, for platforms where nothing else
 *                         guarantees it exists
 * @param ingressProvisioners the provisioners of the ingress, eg to remove its finalizers on destroy, for platforms
 *                         that don't do so themselves
 */
public record HttpsRoute(String component,
                         String domainName,
                         String service,
                         int port,
                         String ingressName,
                         HealthCheck healthCheck,
                         String tlsSecretName,
                         boolean cacheCertificate,
                         List<? extends TerraformResource> dependsOn,
                         Optional<List<LocalExecProvisioner>> ingressProvisioners) {
    public HttpsRoute {
        Objects.requireNonNull(healthCheck, "health check of " + component);
    }
}
