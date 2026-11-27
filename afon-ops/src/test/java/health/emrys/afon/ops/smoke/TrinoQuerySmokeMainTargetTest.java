package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the Trino test queries depends on the mode: the table that {@code spark-polaris} writes outside a project, and in a project the tables that each Data Provider has released and has
 * not, which a member may and may not read.
 */
class TrinoQuerySmokeMainTargetTest {

    private static AfonCatalog catalog(String id) {
        return AfonCatalog.adHoc(id, AfonCloud.AWS, "rest", "bucket", false);
    }

    @Test
    void outsideAProjectItCountsTheTableOfTheCloudsCatalogAndRefusesNothingElse() {
        TrinoQuerySmokeMain.Target target = TrinoQuerySmokeMain.targetFor(false, AfonCloud.AWS, List.of());

        assertThat(target.path()).startsWith("aws/");
        assertThat(target.succeeding()).hasSize(1).first().asString().startsWith("SELECT COUNT(*) FROM ");
        assertThat(target.refused()).isEmpty();
    }

    @Test
    void inAProjectAMemberCountsTheReleasedTableOfEachDataProviderAndIsRefusedTheOneInStaging() {
        TrinoQuerySmokeMain.Target target = TrinoQuerySmokeMain.targetFor(true, AfonCloud.AWS, List.of(catalog("lon_cvd"), catalog("NW_Diab")));

        // the names in Trino are the ids in lower case, and the queries name the catalog, the namespace and the table, so the URL has no default
        assertThat(target.path()).isEmpty();
        assertThat(target.succeeding()).containsExactly("SELECT COUNT(*) FROM lon_cvd.released.smoke_test", "SELECT COUNT(*) FROM nw_diab.released.smoke_test");
        assertThat(target.refused()).containsExactly("SELECT COUNT(*) FROM lon_cvd.staging.smoke_test_unreleased", "SELECT COUNT(*) FROM nw_diab.staging.smoke_test_unreleased");
    }

    @Test
    void inAProjectThatReadsNoDataProviderThereIsNothingToQuery() {
        assertThatThrownBy(() -> TrinoQuerySmokeMain.targetFor(true, AfonCloud.AWS, List.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("dataProviders");
    }

    @Test
    void theUrlHasTheDefaultCatalogAndSchemaOfTheTargetIfItHasAny() {
        TrinoQuerySmokeMain app = new TrinoQuerySmokeMain(AfonCloud.AWS, org.mockito.Mockito.mock(health.emrys.afon.config.AfonPropertyProvider.class));
        Path trust = Path.of("/tmp/ca.pem");

        assertThat(app.httpsUrl(4321, trust, new TrinoQuerySmokeMain.Target("aws/admin", List.of("q"), List.of())))
            .isEqualTo("jdbc:trino://localhost:4321/aws/admin?SSL=true&SSLVerification=FULL&SSLTrustStorePath=" + trust.toAbsolutePath());
        assertThat(app.httpsUrl(4321, trust, new TrinoQuerySmokeMain.Target("", List.of("q"), List.of())))
            .startsWith("jdbc:trino://localhost:4321/?SSL=true");
    }
}
