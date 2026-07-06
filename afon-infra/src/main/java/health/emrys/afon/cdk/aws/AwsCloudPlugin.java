package health.emrys.afon.cdk.aws;

import health.emrys.afon.config.deployment.CloudSpec;
import health.emrys.afon.platform.CloudProviderPlugin;
import health.emrys.afon.platform.DeploymentContext;
import health.emrys.afon.platform.ProvisionedCloud;
import software.constructs.Construct;

/**
 * Provisions an AWS cloud: EKS + VPC + Polaris + KeyCloak, exactly as
 * {@code AwsKubernetesPolaris} builds it today. Phase 2 supports only the
 * single legacy instance (stack id "aws", values from properties); arbitrary
 * ids and per-spec values arrive with the Phase 3 planner.
 */
public class AwsCloudPlugin implements CloudProviderPlugin {

    @Override
    public String providerName() {
        return CloudSpec.PROVIDER_AWS;
    }

    @Override
    public ProvisionedCloud provision(Construct scope, CloudSpec spec, DeploymentContext ctx) {
        CloudProviderPlugin.requireLegacyId(spec, AwsKubernetesPolaris.ID.toString());
        return new AwsKubernetesPolaris(scope, AwsKubernetesPolaris.ID, ctx.properties());
    }
}
