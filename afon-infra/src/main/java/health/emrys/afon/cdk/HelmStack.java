package health.emrys.afon.cdk;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.aws.eks_addon.EksAddon;
import com.hashicorp.cdktf.providers.aws.provider.AwsProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProvider;
import com.hashicorp.cdktf.providers.kubernetes.provider.KubernetesProviderConfig;
import software.constructs.Construct;

import static health.emrys.afon.infra.Defaults.AWS_REGION;
import static health.emrys.afon.infra.Defaults.CLUSTER_NAME;

/**
 * You need to export KUBE_CONFIG_PATH when you run the output of this in tofu apply.
 * For example <pre>export KUBE_CONFIG_PATH=/home/henryp/.kube/config</pre>
 */
class HelmStack extends TerraformStack {

    public HelmStack(final Construct scope, final String id) {
        super(scope, id);

        AwsProvider.Builder.create(this, "aws")
                           .region(AWS_REGION) // Set your desired AWS Region
                           .build();

        EksAddon.Builder.create(this, "vpc-cni")
                        .clusterName(CLUSTER_NAME)
                        .region(AWS_REGION)
                        .addonName("vpc-cni")
                        // Setting resolveConflicts to OVERWRITE forces the update even if manual changes exist
                        .resolveConflictsOnUpdate("OVERWRITE")
                        .build();

        // 1. Configure the Kubernetes Provider
        // The Helm provider relies on the Kubernetes provider to connect to the cluster.
        // We configure it to use the default kubeconfig path (~/.kube/config).
        // This provider must be set up first.
        String configPath = System.getProperty("user.home") + "/.kube/config";
        KubernetesProvider.Builder.create(this, "kubernetes")
                                  .configPath(configPath)
                                  .build();

        KubernetesProviderConfig.builder().configPath(configPath).build();

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
                       .repository("https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts")
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();

    }

    public static void main(String[] args) {
        final App app = new App();
        new HelmStack(app, "helm-secrets-store-csi-driver");
        app.synth();
    }
}

