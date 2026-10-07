package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.ingress_v1.IngressV1;

/**
 * How a component was made reachable, see {@link CloudPlatform#exposeHttps(HttpsRoute)}.
 *
 * @param ingress   the ingress routing to the component
 * @param dnsRecord the record resolving the component's domain name, which anything performing a lookup of it on
 *                  startup, eg OIDC discovery, must wait for, or it may start while the name does not exist
 */
public record HttpsExposure(IngressV1 ingress, ITerraformDependable dnsRecord) {
}
