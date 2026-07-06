package health.emrys.afon.platform;

import health.emrys.afon.config.deployment.AfonDeploymentConfig;

import java.util.Optional;
import java.util.Properties;

/**
 * Everything a plugin may need beyond its own spec: the full deployment
 * configuration (absent on the legacy, properties-only path) and the flat
 * properties that still carry secrets and, during migration, topology values.
 *
 * @param config     the declarative deployment description; empty when invoked
 *                   from the legacy {@code Deploy*Main} path (Phase 2)
 * @param properties the merged {@code afon.properties} + {@code env.properties}
 */
public record DeploymentContext(Optional<AfonDeploymentConfig> config, Properties properties) {

    public static DeploymentContext legacy(Properties properties) {
        return new DeploymentContext(Optional.empty(), properties);
    }

    public static DeploymentContext of(AfonDeploymentConfig config, Properties properties) {
        return new DeploymentContext(Optional.of(config), properties);
    }
}
