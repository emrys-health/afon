package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;

import java.util.List;

public interface CloudProvider {

    List<ITerraformDependable> getDependables();

    String getBase64CertData();

    String getClusterEndpoint();

    String getToken();

    void configureLoadBalancerDestroy(List<Service> loadBalancers);

}
