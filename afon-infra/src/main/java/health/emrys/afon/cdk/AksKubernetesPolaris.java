package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import software.constructs.Construct;

import java.util.List;

public class AksKubernetesPolaris extends AksKubernetesStack {
    public AksKubernetesPolaris(Construct scope, String id) {
        super(scope, id);
        KubernetesProvider kubernetes = KubernetesProvider.Builder.create(this, "kubernetes-local")
                                  .configPath("~/.kube/config")
                                  .build();

        CloudProvider localCloud = new CloudProvider() {

            @Override
            public List<Resource> getDependables() {
                return List.of();
            }

            @Override
            public String getBase64CertData() {
                return cloudProvider.getBase64CertData();
            }

            @Override
            public String getClusterEndpoint() {
                return cloudProvider.getClusterEndpoint();
            }

            @Override
            public String getToken() {
                return kubernetes.getToken();
            }

            @Override
            public void configureLoadBalancerDestroy(List<Service> loadBalancers) {

            }
        };
        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", localCloud) {
            @Override
            protected void configureK8s(CloudProvider cloudProvider) { }
        };
        polarisConfig.deployPolaris(List.of());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AksKubernetesPolaris(app, ID);
        app.synth();
    }
}
