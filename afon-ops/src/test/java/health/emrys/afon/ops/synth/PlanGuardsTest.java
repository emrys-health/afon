package health.emrys.afon.ops.synth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A first deploy of an AWS stack is refused if the cloud already has a VPC with the name of the stack's VPC, as a second VPC
 * of the same name breaks the by-name cleanup of the stack.
 */
class PlanGuardsTest {

    private static final String PLAN = """
        {"provider": {"aws": [{"region": "eu-west-2"}]},
         "resource": {"aws_vpc": {"aws_eksVpc_7BAF8DF4": {"tags": {"Name": "cdktf-eks-vpc-full-emrys2-cluster"}}}}}
        """;

    @TempDir
    Path dir;

    private List<String> found = List.of();
    private String queried;

    private final PlanGuards guards = new PlanGuards() {
        @Override
        protected List<String> vpcIdsNamed(String name, String region) {
            queried = name + " in " + region;
            return found;
        }
    };

    private Path stackDir() throws IOException {
        Files.writeString(dir.resolve("cdk.tf.json"), PLAN);
        return dir;
    }

    private Path state(String resources) throws IOException {
        return Files.writeString(dir.resolve("terraform.tfstate"), "{\"resources\": [" + resources + "]}");
    }

    @Test
    void refusesFirstDeployWhenAVpcOfTheNameExists() throws Exception {
        found = List.of("vpc-1", "vpc-2");

        assertThatThrownBy(() -> guards.checkNoOrphanAwsVpc(stackDir(), dir.resolve("none.tfstate")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cdktf-eks-vpc-full-emrys2-cluster")
            .hasMessageContaining("vpc-1")
            .hasMessageContaining("vpc-2");
        assertThat(queried).isEqualTo("cdktf-eks-vpc-full-emrys2-cluster in eu-west-2");
    }

    @Test
    void allowsFirstDeployWhenNoVpcOfTheNameExists() {
        assertThatCode(() -> guards.checkNoOrphanAwsVpc(stackDir(), dir.resolve("none.tfstate"))).doesNotThrowAnyException();
    }

    @Test
    void allowsRedeployWhenTheStateHasTheVpc() throws Exception {
        found = List.of("vpc-1");

        assertThatCode(() -> guards.checkNoOrphanAwsVpc(stackDir(), state("{\"type\": \"aws_vpc\", \"instances\": [{}]}")))
            .doesNotThrowAnyException();
        assertThat(queried).isNull();
    }
}
