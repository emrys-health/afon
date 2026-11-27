package health.emrys.afon.ops;

import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.ops.plan.StackPlanFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManageStackMainTest {

    private final StackPlanFactory factory = new StackPlanFactory();
    private final ManageStackMain manager = new ManageStackMain(factory, new FileUserProvider());

    @Test
    void canResolveActionOnStackCaseInsensitively() {
        assertThat(manager.stepsProviderFor("deploy", "aws-keycloak")).isNotNull();
        assertThat(manager.stepsProviderFor("SETUP", "GCP-Polaris", true)).isNotNull();
        assertThat(manager.stepsProviderFor("Delete", "AWS-All")).isNotNull();
    }

    @Test
    void canNameAStackByItsRoleInTheCloudAsWellAsByItsId() {
        assertThat(manager.stepsProviderFor("setup", "aws-tre")).isNotNull();
        assertThat(manager.stepsProviderFor("deploy", "AZURE-Catalog", true)).isNotNull();
        assertThat(manager.stepsProviderFor("delete", "aws-identity")).isNotNull();
        assertThat(manager.stepsProviderFor("setup", "gcp-identity")).isNotNull();
        assertThat(manager.stepsProviderFor("setup", "gcp-tre")).isNotNull();
        // the scenarios of the stack with the role are those of the stack: the snowflake stack has no role, the full one has none
        AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
        assertThat(manager.runProviderFor("aws-tre", "external-azure", properties) != null)
            .isEqualTo(manager.runProviderFor("aws-full", "external-azure", properties) != null);
        assertThat(manager.runProviderFor("aws-tre", "reading-azure", properties)).isNull();
    }

    @Test
    void canManageEveryStackDefined() {
        // including those that no command managed before, as they are made of components
        assertThat(manager.stepsProviderFor("deploy", "azure-full")).isNotNull();
        assertThat(manager.stepsProviderFor("setup", "aws-keycloak")).isNotNull();
        assertThat(manager.stepsProviderFor("setup", "aws-lab")).isNotNull();
        assertThat(manager.stepsProviderFor("delete", "aws-snowflake")).isNotNull();
    }

    @Test
    void canOnlyDeleteEveryStackOfACloud() {
        assertThat(manager.stepsProviderFor("deploy", "aws-all")).isNull();
        assertThat(manager.stepsProviderFor("setup", "aws-all")).isNull();
    }

    @Test
    void cannotResolveUnknownActionOrStack() {
        assertThat(manager.stepsProviderFor("destroy", "aws-keycloak")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "aws-unknown")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "azure-unknown")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "mars-keycloak")).isNull();
        assertThat(manager.stepsProviderFor("deploy", "keycloak")).isNull();
    }

    @Test
    void runDistinguishesAFlowFromAStack() {
        assertThat(manager.isFlow("cross-cloud-azure")).isTrue();
        assertThat(manager.isFlow("aws-full")).isFalse();
        assertThat(manager.isFlow("aws-snowflake")).isFalse();
        assertThat(manager.isFlow("unknown")).isFalse();
    }

    @Test
    void canResolveScenarioOfStack() {
        AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");
        assertThat(manager.runProviderFor("aws-snowflake", "reading-azure", properties)).isNotNull();
        assertThat(manager.runProviderFor("aws-snowflake", "reading-mars", properties)).isNull();
        assertThat(manager.runProviderFor("aws-full", "reading-azure", properties)).isNull();
        assertThat(manager.runProviderFor("aws-unknown", "reading-azure", properties)).isNull();
        assertThat(manager.runProviderFor("snowflake", "reading-azure", properties)).isNull();
    }

    @Test
    void stackIdsAreNamedAsValidateStackNamesTheStacks() {
        assertThat(new CloudStackValidator().stackNames())
            .containsAll(factory.supportedActions().keySet().stream()
                                .filter(stackId -> !stackId.isAll())
                                .map(StackId::toString)
                                .toList());
    }


    private static String printedBy(Runnable command) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            command.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void validateIsTheValidateStackCommandAmongTheActionsOnAStack() {
        // without a stack, as validate-stack does, it lists the stacks
        String viaManageStack = printedBy(() -> ManageStackMain.main(new String[]{"validate"}));
        String viaValidateStack = printedBy(() -> health.emrys.afon.ops.stack.ValidateStackMain.main(new String[0]));

        assertThat(viaManageStack).isNotBlank().contains("Usage: validate-stack").isEqualTo(viaValidateStack);
    }

    @Test
    void validateHasTheHelpOfValidateStack() {
        assertThat(printedBy(() -> ManageStackMain.main(new String[]{"validate", "-h"}))).contains("Validates the config of a stack by synthesising its plan", "--runs <n>");
    }

    @Test
    void theUsageOfManageStackListsValidate() {
        assertThat(printedBy(() -> ManageStackMain.main(new String[]{"-h"}))).contains("manage-stack validate [<stack-id> [--runs <n>] | --export-stacks]");
    }

    private static void writePlan(java.nio.file.Path dir, String stack, String name) throws Exception {
        java.nio.file.Path plan = java.nio.file.Files.createDirectories(dir.resolve(stack));
        java.nio.file.Files.writeString(plan.resolve("cdk.tf.json"), "{\"resource\":{\"aws_x\":{\"a\":{\"name\":\"" + name + "\"}}}}");
    }

    @Test
    void compareListsNothingForTwoSnapshotsOfTheSamePlanAndDoesNotExit(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        writePlan(dir.resolve("before"), "aws-full", "n");
        writePlan(dir.resolve("after"), "aws-full", "n");

        // no exit, as the plans do not differ: what is printed says so
        String out = printedBy(() -> ManageStackMain.main(new String[]{"compare", dir.resolve("before").toString(), dir.resolve("after").toString()}));

        assertThat(out).isNotBlank().doesNotContain("Usage:");
    }

    @Test
    void stacksAreNamedByIdOrByRoleInTheCloud() {
        assertThat(ManageStackMain.named("aws-full")).isEqualTo("aws-full");
        assertThat(ManageStackMain.named("aws-tre")).isEqualTo("aws-full");
        assertThat(ManageStackMain.named("AZURE-Catalog")).isEqualTo("azure-polaris");
    }

    @Test
    void whatIsNotAStackOfTheStacksFileIsLeftAsItIs() {
        // eg the dir of a stack synthesised with another stacks file, or the legacy stack of the cloud
        assertThat(ManageStackMain.named("aws-nonesuch")).isEqualTo("aws-nonesuch");
        assertThat(ManageStackMain.named("gcp-lab")).isEqualTo("gcp-lab");
        assertThat(ManageStackMain.named("aws")).isEqualTo("aws");
    }

    @Test
    void compareHasItsHelp() {
        assertThat(printedBy(() -> ManageStackMain.main(new String[]{"compare", "--help"}))).contains("Compares the plans of the stacks in two dirs", "afon-cli.sh manage-stack compare <before-dir> <after-dir>");
    }

    @Test
    void theUsageOfManageStackListsCompare() {
        assertThat(printedBy(() -> ManageStackMain.main(new String[]{"-h"}))).contains("manage-stack compare <before-dir> <after-dir>");
    }

    @Test
    void snapshotCopiesThePlansThatWereSynthesised(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("AFON_CDKTF") == null && System.getenv("AFON_HOME") == null, "the environment names a cdktf dir");
        // a stack that was synthesised, in the stacks dir of the cdktf dir
        java.nio.file.Path plan = java.nio.file.Files.createDirectories(dir.resolve("cdktf/stacks/aws-full"));
        java.nio.file.Files.writeString(plan.resolve("cdk.tf.json"), "{\"resource\":{}}");
        System.setProperty("afon.cdktf", dir.resolve("cdktf").toString());
        try {
            String out = printedBy(() -> ManageStackMain.main(new String[]{"snapshot", dir.resolve("snapshot").toString(), "aws-full"}));

            assertThat(out).contains("Snapshot of 1 stacks from " + dir.resolve("cdktf/stacks"), "aws-full");
            assertThat(dir.resolve("snapshot/aws-full/cdk.tf.json")).exists();
        } finally {
            System.clearProperty("afon.cdktf");
        }
    }

    @Test
    void snapshotHasItsHelp() {
        assertThat(printedBy(() -> ManageStackMain.main(new String[]{"snapshot", "-h"}))).contains("Snapshots the plans that were synthesised", "afon-cli.sh manage-stack snapshot <dir> [<stack-id>...]");
    }

    @Test
    void theUsageOfManageStackListsSnapshot() {
        assertThat(printedBy(() -> ManageStackMain.main(new String[]{"--help"}))).contains("manage-stack snapshot <dir> [<stack-id>...]");
    }
}
