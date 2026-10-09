package health.emrys.afon.ops.snowflake;

import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.OIDC_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.OIDC_PROTOCOL;
import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeStatementsTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    @Test
    void readingViaAwsPolarisRunsOnAnAccountWhereItHasNeverRun() {
        properties.setValue(AWS_POLARIS_DOMAIN_NAME, "polaris.example");
        String[] statements = SnowflakeStatements.readingViaAwsPolaris(new AfonCatalogs(properties).aws(), properties);
        // nothing to drop the first time, so the drops must not fail
        for (String statement : statements) {
            if (statement.startsWith("DROP ")) {
                assertThat(statement).contains("IF EXISTS");
            }
        }
        assertThat(statements[0]).isEqualTo("USE DATABASE SNOWFLAKE_LEARNING_DB");
        assertThat(statements[1]).isEqualTo("DROP ICEBERG TABLE IF EXISTS my_aws_table_id");
        assertThat(statements[2]).isEqualTo("DROP CATALOG INTEGRATION IF EXISTS iceberg_aws_id");
    }

    @Test
    void eachCatalogIsReadThroughObjectsOfItsOwn() {
        properties.setValue(AWS_POLARIS_DOMAIN_NAME, "polaris.example");
        AfonCatalogs catalogs = new AfonCatalogs(properties);
        for (var catalog : List.of(catalogs.aws(), catalogs.azure(), catalogs.gcp())) {
            String id = catalog.id();
            String[] statements = SnowflakeStatements.readingViaAwsPolaris(catalog, properties);
            assertThat(String.join("\n", statements))
                .contains("CREATE OR REPLACE CATALOG INTEGRATION iceberg_" + id + "_id")
                .contains("CREATE OR REPLACE ICEBERG TABLE my_" + id + "_table_id")
                .contains("CATALOG = 'iceberg_" + id + "_id'")
                .contains("SELECT COUNT(*) FROM my_" + id + "_table_id");
        }
        assertThat(SnowflakeStatements.integrationName(catalogs.azure())).isNotEqualTo(SnowflakeStatements.integrationName(catalogs.gcp()));
    }

    @Test
    void readingViaAwsPolarisGetsItsTokenFromKeyCloakAsPolarisDoesNotIssueAny() {
        properties.setValue(AWS_POLARIS_DOMAIN_NAME, "polaris.example");
        properties.setValue(OIDC_DOMAIN_NAME, "oauth.example");
        properties.setValue(OIDC_PROTOCOL, "https");
        String integration = SnowflakeStatements.readingViaAwsPolaris(new AfonCatalogs(properties).aws(), properties)[3];
        assertThat(integration).contains("CATALOG_URI = 'https://polaris.example/api/catalog/'")
                               .contains("OAUTH_TOKEN_URI = 'https://oauth.example/realms/polaris/protocol/openid-connect/token'")
                               .contains("OAUTH_ALLOWED_SCOPES = ('openid')")
                               .doesNotContain("PRINCIPAL_ROLE:ALL");
    }
}
