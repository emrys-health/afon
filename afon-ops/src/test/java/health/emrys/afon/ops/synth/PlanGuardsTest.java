package health.emrys.afon.ops.synth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A first deploy is refused if the cloud already has resources of the stack, and a delete is refused, keeping the state and the
 * stack dir, if the cloud still has them after the destroy, whatever the state says.
 */
class PlanGuardsTest {

    @TempDir
    Path dir;

    private List<String> found = List.of();
    private int lookups;

    private final PlanGuards guards = new PlanGuards(new CloudLeftovers() {
        @Override
        public List<String> in(Path stackDir) {
            lookups++;
            return found;
        }
    });

    private Path state(String resources) throws IOException {
        return Files.writeString(dir.resolve("terraform.tfstate"), "{\"resources\": [" + resources + "]}");
    }

    private static final String MANAGED = "{\"mode\": \"managed\", \"type\": \"aws_vpc\", \"instances\": [{}]}";

    @Test
    void refusesFirstDeployWhenTheCloudHasResourcesOfTheStack() {
        found = List.of("EKS cluster full: full", "VPC named v: vpc-1");

        assertThatThrownBy(() -> guards.checkNoneLeftBeforeDeploy(dir, dir.resolve("none.tfstate")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("2 resource(s)")
            .hasMessageContaining("EKS cluster full: full")
            .hasMessageContaining("VPC named v: vpc-1");
    }

    @Test
    void refusesDeployWhenTheStateIsEmptyAndTheCloudHasResources() throws Exception {
        found = List.of("AKS cluster full-emrys-cluster in resource group rg: id");

        assertThatThrownBy(() -> guards.checkNoneLeftBeforeDeploy(dir, state("")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("full-emrys-cluster");
    }

    @Test
    void allowsFirstDeployWhenTheCloudHasNone() {
        assertThatCode(() -> guards.checkNoneLeftBeforeDeploy(dir, dir.resolve("none.tfstate"))).doesNotThrowAnyException();
    }

    @Test
    void allowsRedeployWhenTheStateHasResourcesWithoutLookingInTheCloud() throws Exception {
        found = List.of("VPC named v: vpc-1");

        assertThatCode(() -> guards.checkNoneLeftBeforeDeploy(dir, state(MANAGED))).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(lookups).isZero();
    }

    @Test
    void refusesDeleteWhenTheStateIsEmptyButTheClusterIsStillInTheCloud() throws Exception {
        found = List.of("AKS cluster full-emrys-cluster in resource group rg: id");

        assertThatThrownBy(() -> guards.checkNoneLeftAfterDestroy(dir, state("")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("still has 1 resource(s)")
            .hasMessageContaining("full-emrys-cluster")
            .hasMessageContaining("is deleted");
    }

    @Test
    void allowsDeleteWhenTheCloudHasNone() throws Exception {
        assertThatCode(() -> guards.checkNoneLeftAfterDestroy(dir, state(""))).doesNotThrowAnyException();
    }
}
