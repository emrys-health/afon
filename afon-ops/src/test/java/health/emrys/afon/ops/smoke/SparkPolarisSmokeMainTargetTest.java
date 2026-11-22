package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Spark write test writes: the stack's own catalog outside a project, as ever, and in a Data Provider's project its own staging namespace.
 */
class SparkPolarisSmokeMainTargetTest {

    @TempDir
    Path dir;

    private SparkPolarisSmokeMain smoke(String properties) throws IOException {
        Files.writeString(dir.resolve("afon.properties"), properties);
        return new SparkPolarisSmokeMain(new FilePropertyProvider(dir.toString(), List.of("afon")));
    }

    @Test
    void aDataProviderWritesTwoTablesInTheStagingNamespaceOfItsFirstCatalogOnItsOwnCloud() throws IOException {
        SparkPolarisSmokeMain smoke = smoke("""
            OIDC_DOMAIN_NAME=oidc.example
            OIDC_ISSUER_URL=https://oidc.example/realms/polaris
            AZURE_OIDC_DOMAIN_NAME=oidc.example
            PROJECT_ID=lon
            POLARIS_ID=root
            AZURE_CATALOG_IDS=cvd,dbt
            AZURE_CATALOG_TYPE_cvd=internal
            AZURE_CATALOG_TYPE_dbt=internal
            """);
        assertThat(smoke.targets()).containsExactly(new String[]{"azure", "cvd", "staging", "smoke_test"},
                                                    new String[]{"azure", "cvd", "staging", "smoke_test_unreleased"});
    }

    @Test
    void outsideAProjectItWritesWhateverIsGivenAsEver() throws Exception {
        SparkPolarisSmokeMain smoke = smoke("""
            OIDC_DOMAIN_NAME=oidc.example
            OIDC_ISSUER_URL=https://oidc.example/realms/polaris
            AWS_OIDC_DOMAIN_NAME=oidc.example
            TRE_CLOUD=aws
            AWS_CATALOG_IDS=aws
            AWS_CATALOG_TYPE_aws=internal
            """);
        List<String[]> targets = smoke.targets();
        assertThat(smoke.notThereYet(targets, "localhost:1")).isSameAs(targets);
    }

    @Test
    void outsideAProjectItWritesTheStacksOwnCatalogAsEver() throws IOException {
        SparkPolarisSmokeMain smoke = smoke("""
            OIDC_DOMAIN_NAME=oidc.example
            OIDC_ISSUER_URL=https://oidc.example/realms/polaris
            AWS_OIDC_DOMAIN_NAME=oidc.example
            TRE_CLOUD=aws
            AWS_CATALOG_IDS=aws
            AWS_CATALOG_TYPE_aws=internal
            """);
        assertThat(smoke.targets()).hasSize(1);
        assertThat(smoke.targets().get(0)).containsExactly("aws", "aws", "admin", "deployments");
    }
}
