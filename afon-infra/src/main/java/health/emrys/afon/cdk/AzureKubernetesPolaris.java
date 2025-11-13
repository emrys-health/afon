package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import software.constructs.Construct;

import java.util.List;

public class AzureKubernetesPolaris extends AzureKubernetesStack {
    public AzureKubernetesPolaris(Construct scope, String id) {
        super(scope, id);
        KubernetesProvider kubernetes = KubernetesProvider.Builder.create(this, "kubernetes-local")
                                  .configPath("~/.kube/config")
                                  .build();

        CloudProvider localCloud = new CloudProvider() {

            @Override
            public List<ITerraformDependable> getDependables() {
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
        new AzureKubernetesPolaris(app, ID);
        app.synth();
    }
}
