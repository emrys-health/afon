package health.emrys.afon.cdk.gcp;

import health.emrys.afon.config.deployment.CloudSpec;
import health.emrys.afon.platform.CloudProviderPlugin;
import health.emrys.afon.platform.DeploymentContext;
import health.emrys.afon.platform.ProvisionedCloud;
import software.constructs.Construct;

import static health.emrys.afon.platform.CloudProviderPlugin.requireLegacyId;

/**
 * Provisions a GCP cloud: GKE + Polaris behind nginx/cert-manager, exactly as
 * {@code GcpKubernetesPolaris} builds it today. Phase 2 supports only the
 * single legacy instance (stack id "gcp").
 */
public class GcpCloudPlugin implements CloudProviderPlugin {

    @Override
    public String providerName() {
        return CloudSpec.PROVIDER_GCP;
    }

    @Override
    public ProvisionedCloud provision(Construct scope, CloudSpec spec, DeploymentContext ctx) {
        requireLegacyId(spec, GcpKubernetesPolaris.ID.toString());
        return new GcpKubernetesPolaris(scope, spec.id(), ctx.properties());
    }
}
