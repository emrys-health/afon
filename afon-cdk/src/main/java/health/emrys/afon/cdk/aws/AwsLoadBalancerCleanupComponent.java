package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;

import java.util.List;

/**
 * The cleanup, on destroy, of the load balancers and security groups that the AWS load balancer controller leaves
 * behind, once the Kubernetes services are destroyed.
 */
public class AwsLoadBalancerCleanupComponent implements StackComponent {

    @Override
    public void deploy(StackContext context) {
        // The workloads are exposed via an ALB ingress, not a raw LoadBalancer Service, so there are no services to wait for
        ((AwsCloudProvider) context.platform()).configureLoadBalancerDestroy(List.<Service>of());
    }
}
