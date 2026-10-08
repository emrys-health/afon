package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;

import java.util.List;
import java.util.Map;

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

    /**
     * Annotations that the cloud's controllers would otherwise add to the {@link Service} of a workload behind the
     * ingress, which tofu would then report as a change on every plan.  Declaring them makes them part of the
     * plan.  On GCP this is the GKE annotation for network endpoint groups.  Empty by default.
     */
    default Map<String, String> getServiceAnnotations() {
        return Map.of();
    }

    KubernetesProvider configureK8s();

    String getId();

}
