package health.emrys.afon.cdk.azure;

import health.emrys.afon.config.deployment.CloudSpec;
import health.emrys.afon.platform.CloudProviderPlugin;
import health.emrys.afon.platform.DeploymentContext;
import health.emrys.afon.platform.ProvisionedCloud;
import software.constructs.Construct;

import static health.emrys.afon.platform.CloudProviderPlugin.requireLegacyId;

/**
 * Provisions an Azure cloud: AKS + Polaris behind nginx/cert-manager, exactly
 * as {@code AzureKubernetesPolaris} builds it today. Phase 2 supports only the
 * single legacy instance (stack id "azure").
 */
public class AzureCloudPlugin implements CloudProviderPlugin {

    @Override
    public String providerName() {
        return CloudSpec.PROVIDER_AZURE;
    }

    @Override
    public ProvisionedCloud provision(Construct scope, CloudSpec spec, DeploymentContext ctx) {
        requireLegacyId(spec, AzureKubernetesPolaris.ID.toString());
        return new AzureKubernetesPolaris(scope, spec.id(), ctx.properties());
    }
}
