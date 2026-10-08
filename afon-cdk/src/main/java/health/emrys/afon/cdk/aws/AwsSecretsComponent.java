package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.helm.release.Release;
import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;

import static health.emrys.afon.cdk.CdkUtils.vendoredChart;

/**
 * The secrets store CSI driver, the bridge between the secrets of AWS and those mounted in the pods of the EKS cluster.
 */
public class AwsSecretsComponent implements StackComponent {

    @Override
    public void deploy(StackContext context) {
        Release.Builder.create(context.stack(), "secrets-store-csi-driver")
                       .dependsOn(context.platform().getDependables())
                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                       .name("csi-secrets-store") // The release name
                       .chart(vendoredChart(context.stack(), "csiDriverChart", "secrets-store-csi-driver-1.5.4.tgz"))
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
    }
}
