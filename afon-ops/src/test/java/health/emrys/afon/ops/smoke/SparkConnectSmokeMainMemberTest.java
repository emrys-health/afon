package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Spark Connect test needs to tell, for a member who reads the catalogs of the project's Data Providers.
 */
class SparkConnectSmokeMainMemberTest {

    @Test
    void aDataProvidersCatalogIsNamedInSparkAsTheNotebooksNameIt() {
        assertThat(SparkConnectSmokeMain.sparkCatalogOf(AfonCatalog.adHoc("lon_cvd", AfonCloud.AZURE, "rest", "bucket", false))).isEqualTo("test_lon_cvd");
    }

    @Test
    void aFailureThatSaysTheTableIsNotThereIsNotARefusalWhateverLevelOfTheCauseSaysSo() {
        assertThat(SparkConnectSmokeMain.saysTableIsMissing(new RuntimeException("[TABLE_OR_VIEW_NOT_FOUND] The table or view `x` cannot be found"))).isTrue();
        assertThat(SparkConnectSmokeMain.saysTableIsMissing(new RuntimeException("wrapper", new RuntimeException("NoSuchTableException: Table does not exist: staging.t")))).isTrue();
    }

    @Test
    void aForbiddenFailureIsARefusal() {
        assertThat(SparkConnectSmokeMain.saysTableIsMissing(new RuntimeException("ForbiddenException: Principal 'afon:1' with activated PrincipalRoles [] is not authorized"))).isFalse();
    }
}
