package health.emrys.afon.platform;

import health.emrys.afon.config.deployment.EngineSpec;
import health.emrys.afon.demo.Step;
import health.emrys.afon.demo.StepActions;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ID;
import static health.emrys.afon.config.AfonProperties.POLARIS_SECRET;
import static java.lang.String.format;

/**
 * "Deploys" Snowflake. Snowflake is SaaS, so there is nothing to synthesise
 * into a Terraform stack; deployment means executing the catalog-integration
 * SQL (the statements the {@code DeploySnowflakeReading*Main} demos run) so
 * Snowflake can read each catalog in {@code readsCatalogs} through the given
 * cloud's live Polaris. Hence {@link #deploy} is unsupported and all work
 * happens in {@link #runtimeSteps}, which the {@code DeploymentPlanner}
 * schedules after that cloud's infrastructure is applied and its catalogs
 * are initialised.
 * <p>
 * Connection settings (JDBC URL, key) come from properties via
 * {@code SnowflakeConnectionFactory}; the config's {@code jdbcUrlRef} names
 * them but never contains them.
 */
public class SnowflakeEngineDeployer implements QueryEngineDeployer {

    @Override
    public String engineType() {
        return EngineSpec.TYPE_SNOWFLAKE;
    }

    @Override
    public void deploy(ProvisionedCloud target, EngineSpec spec, DeploymentContext ctx) {
        throw new UnsupportedOperationException(
            "Snowflake is SaaS: nothing synthesises into a Terraform stack. Use runtimeSteps().");
    }

    @Override
    public List<Step> runtimeSteps(StepActions actions,
                                   String polarisDomain,
                                   EngineSpec spec,
                                   DeploymentContext ctx) {
        List<String> catalogs = spec.readsCatalogs() == null ? List.of() : spec.readsCatalogs();
        return catalogs.stream()
                       .map(catalog -> integrationStep(actions, polarisDomain, catalog, ctx.properties()))
                       .toList();
    }

    private Step integrationStep(StepActions actions,
                                 String polarisDomain,
                                 String catalog,
                                 Properties properties) {
        return new Step(
            "snowflake_integration_" + catalog,
            format("create the Snowflake catalog integration for `%s` via the Polaris at `%s`",
                   catalog, polarisDomain),
            () -> actions.runSnowflakeSql(new String[]{
                catalogIntegrationSql(
                    polarisDomain,
                    catalog,
                    POLARIS_ID.getValueFrom(properties),
                    POLARIS_SECRET.getValueFrom(properties))
            }));
    }

    /** The SQL the {@code DeploySnowflakeReading*Main} demos established works. */
    static String catalogIntegrationSql(String polarisDomain,
                                        String catalog,
                                        String oauthClientId,
                                        String oauthClientSecret) {
        return format(
            """
            CREATE OR REPLACE CATALOG INTEGRATION %s
              CATALOG_SOURCE = ICEBERG_REST
              TABLE_FORMAT = ICEBERG
              REST_CONFIG = (
                  CATALOG_URI = 'https://%s/api/catalog/',
                  CATALOG_NAME = '%s'
                  ACCESS_DELEGATION_MODE = VENDED_CREDENTIALS
              )
              REST_AUTHENTICATION = (
                  TYPE = OAUTH
                  OAUTH_CLIENT_ID = '%s'
                  OAUTH_CLIENT_SECRET = '%s'
                  OAUTH_ALLOWED_SCOPES = ('PRINCIPAL_ROLE:ALL')
              )
              ENABLED = TRUE;
            """,
            integrationNameFor(catalog),
            polarisDomain,
            catalog,
            oauthClientId,
            oauthClientSecret);
    }

    /** e.g. {@code afon_azure_external} — a valid unquoted Snowflake identifier. */
    static String integrationNameFor(String catalog) {
        return "afon_" + catalog.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
