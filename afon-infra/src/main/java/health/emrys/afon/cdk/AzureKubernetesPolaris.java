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
            public void configureLoadBalancerDestroy(List<Service> loadBalancers) { }

            @Override
            public KubernetesProvider configureK8s() {
                return null;
            }
        };
        PolarisConfig polarisConfig = new PolarisConfig(this, "polaris", localCloud);
        polarisConfig.deployPolaris(List.of());
    }

    public static void main(String[] args) {
        final App app = new App();
        new AzureKubernetesPolaris(app, ID);
        app.synth();
    }
}
