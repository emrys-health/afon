package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProviderConfig;
import software.constructs.Construct;

/**
 * You need to export KUBE_CONFIG_PATH when you run the output of this in tofu apply.
 * For example <pre></pre>export KUBE_CONFIG_PATH=/home/henryp/.kube/config</pre>
 */
class HelmStack extends TerraformStack {

    public HelmStack(final Construct scope, final String id) {
        super(scope, id);

        // 1. Configure the Kubernetes Provider
        // The Helm provider relies on the Kubernetes provider to connect to the cluster.
        // We configure it to use the default kubeconfig path (~/.kube/config).
        // This provider must be set up first.
        KubernetesProvider.Builder.create(this, "kubernetes")
                                  .configPath("=/home/henryp/.kube/config")
                                  .build();

        KubernetesProviderConfig.builder().configPath("~/.kube/config").build();

        // 2. Configure the Helm Provider
        // CDKTF automatically detects the Kubernetes provider and links the Helm provider to it.
        // If the Kubernetes provider is named 'kubernetes' (as above), it is often implicit.
        HelmProvider.Builder.create(this, "helm")
                            // No explicit configuration needed here, but you could add experimental features
                            // or specific connection details if needed.
                            .build();


        // 3. Install a Helm Chart (equivalent to 'helm install')
        // This single `Release` resource handles both adding the repository URL and installing the chart.
        Release.Builder.create(this, "secrets-store-csi-driver")
                       .name("csi-secrets-store") // The release name
                       .chart("secrets-store-csi-driver") // The chart name within the repository
                       // **This is where you specify the repository URL (equivalent to 'helm repo add')**
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
//                       .version("18.6.0") // Specify a stable version
                       .namespace("kube-system") // Target Kubernetes namespace
//                       .createNamespace(true) // Automatically create the namespace if it doesn't exist
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release

                       // Override chart values using the 'set' property (equivalent to '--set key=value')
//                       .set(List.of(
//                               ReleaseSet.builder()
//                                         .name("master.persistence.enabled")
//                                         .value("false")
//                                         .build(),
//                               ReleaseSet.builder()
//                                         .name("replica.replicaCount")
//                                         .value("2")
//                                         .build()
//                                   ))
                       // Override values using a map (equivalent to setting 'values.yaml' content)
//                       .values(List.of(
//                               // Using a full JSON string for values is common for complex overrides
//                               "{\"architecture\":\"standalone\"}"
//                                      ))
                       .build();

    }

    public static void main(String[] args) {
        final App app = new App();
        new HelmStack(app, "helm-secrets-store-csi-driver");
        app.synth();
    }
}

