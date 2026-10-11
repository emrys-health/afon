package health.emrys.afon.ops.docs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentNamesTest {

    private static String name(String... args) {
        return DocumentNames.forArgs(args).orElse(null);
    }

    @Test
    void namesTheDocOfAnActionOnAStack() {
        assertThat(name("setup", "aws-full")).isEqualTo("setup_aws_full_stack.md");
        assertThat(name("deploy", "gcp-polaris", "some_step")).isEqualTo("deploy_gcp_polaris_stack.md");
        assertThat(name("delete", "aws-all")).isEqualTo("delete_aws_stack.md");
        // deleting just a stack is documented with deleting the cloud
        assertThat(name("delete", "aws-full")).isEqualTo("delete_aws_stack.md");
    }

    @Test
    void namesTheDocOfAFlowOrScenario() {
        assertThat(name("run", "cross-cloud-azure")).isEqualTo("flow_cross_cloud_azure.md");
        assertThat(name("run", "aws-snowflake", "reading-azure")).isEqualTo("run_aws_snowflake_reading_azure.md");
        assertThat(name("run", "aws-snowflake", "reading-azure", "some_step")).isEqualTo("run_aws_snowflake_reading_azure.md");
    }

    @Test
    void namesNothingForOtherArgs() {
        assertThat(name("setup", "nope")).isNull();
        assertThat(name("setup")).isNull();
        assertThat(name("nope", "aws-full")).isNull();
        assertThat(name("run", "aws-snowflake")).isNull();
    }

}
