package health.emrys.afon.cdk.aws;

import com.hashicorp.cdktf.ITerraformDependable;
import health.emrys.afon.cdk.TrinoWorkloads;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;

public class AwsTrinoStack extends AwsSparkStack {
    public AwsTrinoStack(@NonNull Construct scope, @NonNull StackId stackId, AfonPropertyProvider properties) {
        super(scope, stackId, properties);
        // Trino only needs the cluster reachable (not the Spark operator), so it depends on the
        // EKS dependables rather than eksDependOn.
        // The chart also creates a Service, and the AWS LB Controller's mservice.elbv2.k8s.aws
        // webhook (failurePolicy=Fail) intercepts every Service creation, not just
        // type: LoadBalancer. So we additionally wait on the controller's Helm release —
        // installed atomically, so Helm blocks until its pods are Ready — otherwise the webhook
        // Service can still have zero endpoints and the release fails with "no endpoints
        // available for service aws-load-balancer-webhook-service".
        List<ITerraformDependable> dependsOn = new ArrayList<>(cloudProvider.getDependables());
        dependsOn.addAll(cloudProvider.getServiceDependables());
        new TrinoWorkloads(properties, awsPolarisDomainName)
            .deploy(this, helmProvider, ConfigUtils.cloudToCatalog(stackId.cloud(), new AfonCatalogs(properties)), dependsOn);
    }
}
