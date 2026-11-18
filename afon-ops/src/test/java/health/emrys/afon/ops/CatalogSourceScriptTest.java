package health.emrys.afon.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scripts that make and delete the storage of a stack's catalogs loop over {@code <CLOUD>_CATALOG_IDS}, and must skip the catalogs of a Data Provider, whose
 * storage is not theirs.  They do it with {@code scripts/config/catalog-source.sh}, run here by bash as they run it, over the catalogs file of a TRE project.
 */
class CatalogSourceScriptTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    private List<String> ownIds(String cloud, String catalogs) throws IOException, InterruptedException {
        Path file = dir.resolve("catalogs.properties");
        Files.writeString(file, catalogs);
        // as the launcher gives them, set -a, so that a script reads the properties as variables
        String script = "set -euo pipefail; set -a; source '" + file + "'; set +a; source '" + ROOT + "/scripts/config/catalog-source.sh'; "
                        + "IFS=',' read -ra CATALOG_IDS <<< \"$" + cloud + "_CATALOG_IDS\"; own_catalog_ids " + cloud + " CATALOG_IDS; "
                        + "for ID in ${CATALOG_IDS[@]+\"${CATALOG_IDS[@]}\"}; do echo \"$ID\"; done";
        Process process = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(out).isZero();
        return out.lines().filter(l -> !l.isBlank()).toList();
    }

    @Test
    void skipsTheCatalogsOfADataProviderAndKeepsTheOrderOfTheOthers() throws Exception {
        assertThat(ownIds("AWS", """
            AWS_CATALOG_IDS=aws,lon_cvd,aws_forbidden,nw_cvd
            AWS_CATALOG_SOURCE_lon_cvd=data-provider
            AWS_CATALOG_SOURCE_nw_cvd=data-provider
            AWS_CATALOG_SOURCE_aws=stack
            AWS_CATALOG_URI_lon_cvd=https://azure-polaris.lon.example/api/catalog
            """)).containsExactly("aws", "aws_forbidden");
    }

    @Test
    void leavesEveryCatalogOfAConfigWithNoSourceAsItWas() throws Exception {
        assertThat(ownIds("AZURE", "AZURE_CATALOG_IDS=azure,azure_external,azure_forbidden\n")).containsExactly("azure", "azure_external", "azure_forbidden");
    }

    @Test
    void leavesNoneWhenEveryCatalogIsADataProvidersAndWhenThereAreNone() throws Exception {
        assertThat(ownIds("GCP", "GCP_CATALOG_IDS=lon_cvd\nGCP_CATALOG_SOURCE_lon_cvd=data-provider\n")).isEmpty();
        assertThat(ownIds("GCP", "GCP_CATALOG_IDS=\n")).isEmpty();
    }
}
