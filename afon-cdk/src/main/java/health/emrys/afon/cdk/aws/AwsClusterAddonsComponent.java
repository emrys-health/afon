package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.TerraformResourceLifecycle;
import com.hashicorp.cdktf.providers.helm.release.Release;
import com.hashicorp.cdktf.providers.kubernetes.service.Service;
import health.emrys.afon.cdk.component.StackComponent;
import health.emrys.afon.cdk.component.StackContext;

import java.util.List;

import static health.emrys.afon.cdk.CdkUtils.vendoredChart;

/**
 * What the EKS cluster runs besides the workloads: the secrets store CSI driver, and the cleanup, on destroy, of the
 * load balancers and security groups the AWS load balancer controller leaves behind.
 */
public class AwsClusterAddonsComponent implements StackComponent {

    @Override
    public void deploy(StackContext context) {
        AwsCloudProvider cloudProvider = (AwsCloudProvider) context.platform();
        Release.Builder.create(context.stack(), "secrets-store-csi-driver")
                       .dependsOn(cloudProvider.getDependables())
                       .lifecycle(TerraformResourceLifecycle.builder().preventDestroy(false).build())
                       .name("csi-secrets-store") // The release name
                       .chart(vendoredChart(context.stack(), "csiDriverChart", "secrets-store-csi-driver-1.5.4.tgz"))
                       .namespace("kube-system") // Target Kubernetes namespace
                       .timeout(300) // Wait up to 300 seconds for the deployment to stabilize
                       .atomic(true) // If installation fails, completely uninstall the release
                       .build();
        // Jupyter is exposed via an ALB ingress, not a raw LoadBalancer Service, so there are none to wait for
        cloudProvider.configureLoadBalancerDestroy(List.<Service>of());
    }
}
