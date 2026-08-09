package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.App;
import com.hashicorp.cdktf.Fn;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.helm.provider.HelmProvider;
import com.hashicorp.cdktf.providers.helm.provider.HelmProviderKubernetes;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import health.emrys.afon.config.FilePropertyProvider;
import software.constructs.Construct;

import java.util.Map;
import java.util.Properties;

/**
 * This is experimental only. It creates the bare bones of an EKS environment
 * just to see if it works.
 */
public class AwsHelmStack extends TerraformStack {
    protected AwsHelmStack(final Construct scope,
                           final String id,
                           final Properties properties) {
        super(scope, id);
        NullProvider.Builder.create(this, "nullProvider")
                            .build();
        AwsCloudProvider cloudProvider = new AwsCloudProvider(this, id.toString(), properties);
        this.addOverride(
            "terraform.required_providers.helm", Map.of(
                "source", "hashicorp/helm",
                "version", "3.2.0"));
        HelmProviderKubernetes kubernetes = HelmProviderKubernetes
            .builder()
            .host(cloudProvider.getClusterEndpoint())
            .clusterCaCertificate(Fn.base64decode(cloudProvider.getBase64CertData()))
            .token(cloudProvider.getToken())
            .build();
        HelmProvider helm = HelmProvider.Builder
            .create(this, "helm")
            .kubernetes(kubernetes)
            .build();
    }

    public static void main(String[] args) {
        final App app = new App();
        new AwsHelmStack(app, "ops", new FilePropertyProvider().getProperties());
        app.synth();
    }
}
