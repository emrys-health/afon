package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;

import java.util.List;

public interface CloudProvider {

    List<ITerraformDependable> getDependables();

    void configureLoadBalancerDestroy(List<Service> loadBalancers);

    KubernetesProvider configureK8s();

}
