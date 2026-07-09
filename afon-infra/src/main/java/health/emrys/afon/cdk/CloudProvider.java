package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;

import java.util.List;

public interface CloudProvider {

    List<ITerraformDependable> getDependables();

    /**
     * Dependencies that a load-balanced / NodePort {@link Service} must wait on beyond
     * {@link #getDependables()}. On AWS this is the aws-load-balancer-controller Helm release:
     * its mutating webhook (mservice.elbv2.k8s.aws, failurePolicy=Fail) rejects Service creation
     * with "no endpoints available" until the controller pods are Ready. Empty by default.
     */
    default List<ITerraformDependable> getServiceDependables() {
        return List.of();
    }

    void configureLoadBalancerDestroy(List<Service> loadBalancers);

    KubernetesProvider configureK8s();

    String getId();

}
