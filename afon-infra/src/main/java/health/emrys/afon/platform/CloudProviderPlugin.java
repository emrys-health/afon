package health.emrys.afon.platform;

import health.emrys.afon.config.deployment.CloudSpec;
import software.constructs.Construct;

/**
 * Provisions one {@code clouds:} entry from {@code afon-deployment.yaml}:
 * cluster, networking, storage wiring and Polaris. A deployment may hold any
 * number of instances of any provider (multiple AWS accounts, etc.) — that
 * generality lands in Phase 3; Phase 2 implementations still assume the single
 * legacy instance per provider.
 */
public interface CloudProviderPlugin {

    /** "aws", "azure" or "gcp" — matched against {@link CloudSpec#provider()}. */
    String providerName();

    /** Declare the cloud's infrastructure under {@code scope}, one stack per cloud. */
    ProvisionedCloud provision(Construct scope, CloudSpec spec, DeploymentContext ctx);

    /** Guard for the Phase 2 single-instance limitation described above. */
    static void requireLegacyId(CloudSpec spec, String legacyId) {
        if (!legacyId.equals(spec.id())) {
            throw new IllegalArgumentException(
                "Phase 2 supports only the legacy cloud id '" + legacyId + "' for provider '"
                + spec.provider() + "', got '" + spec.id() + "'");
        }
    }
}
