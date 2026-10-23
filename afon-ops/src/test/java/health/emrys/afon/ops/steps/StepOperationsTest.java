package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepOperationsTest {

    @TempDir
    Path cdktfDir;

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    /**
     * The calls to the actions that synthesise the plan of a stack, as {@code <stack-id> with <config id>}, and start it.
     */
    private final List<String> calls = new ArrayList<>();

    private RuntimeException synthesisFailure;

    private final StepActions actions = new StepActions() {
        @Override
        protected void synthesiseStack(StackId stackId, AfonPropertyProvider config, AfonUserProvider users) {
            calls.add("synthesise " + stackId + (config == properties ? " with the config of the operations" : " with another config"));
            if (synthesisFailure != null) {
                throw synthesisFailure;
            }
        }

        @Override
        void startStack(StackId stackId, OutputStream outputStream) {
            calls.add("start " + stackId);
        }

        @Override
        void checkAnswers(String url, String failure) {
            calls.add("check " + url + ": " + failure);
        }

        @Override
        protected void withPortForward(AfonCloud cloud, AfonPropertyProvider config, String podLabel, int podPort,
                                       PortForwardCall call) throws Exception {
            calls.add("port-forward to " + podLabel + ":" + podPort + " of " + cloud);
            call.run("localhost:54321");
        }

        @Override
        public void createCatalogFor(AfonCloud cloud, AfonCatalog catalog, String polarisEndpoint) {
            calls.add("create catalog " + catalog + " in " + cloud + " at " + polarisEndpoint);
        }

        @Override
        public void deleteTableFor(AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName, String polarisEndpoint) {
            calls.add("delete table " + tableName + " of " + catalog + " in " + cloud + " at " + polarisEndpoint);
        }
    };

    private final StepOperations operations = new StepOperations(actions, properties, new FileUserProvider());

    @Test
    void stepsCallingPolarisFromThisMachineGoThroughItsDomainNameOrAPortForwardAsConfigured() throws Throwable {
        Step createCatalog = operations.createExternalCatalogFor(AfonCloud.AZURE);
        Step deleteTable = operations.deleteExternalPolarisTableFor(AfonCloud.AZURE);
        assertThat(createCatalog.isCallingPolaris()).isTrue();
        assertThat(deleteTable.isCallingPolaris()).isTrue();

        properties.setValue(AfonProperty.POLARIS_ACCESS, "https");
        createCatalog.getFunction().run();
        assertThat(calls).hasSize(1).first().asString().endsWith(" in azure at null");

        calls.clear();
        // the default
        properties.setValue(AfonProperty.POLARIS_ACCESS, "");
        createCatalog.getFunction().run();
        deleteTable.getFunction().run();
        assertThat(calls).hasSize(4);
        assertThat(calls.get(0)).isEqualTo("port-forward to app=polaris:8181 of azure");
        assertThat(calls.get(1)).startsWith("create catalog").endsWith(" in azure at localhost:54321");
        assertThat(calls.get(2)).isEqualTo("port-forward to app=polaris:8181 of azure");
        assertThat(calls.get(3)).startsWith("delete table").endsWith(" in azure at localhost:54321");
    }

    @Test
    void callingPolarisIsKeptByComposingStepsAndNotByOthers() throws Throwable {
        Step plain = new Step("plain", "plain", () -> { });
        Step calling = new Step("calling", "calling", () -> { }).callingPolaris();

        assertThat(plain.isCallingPolaris()).isFalse();
        assertThat(Step.sequence("both", "both", plain, calling).isCallingPolaris()).isTrue();
        assertThat(Step.sequence("none", "none", plain, plain).isCallingPolaris()).isFalse();
        assertThat(plain.andThen(calling).isCallingPolaris()).isTrue();
        assertThat(calling.withCleanup(plain).isCallingPolaris()).isTrue();
        assertThat(operations.configurePolarisUsersOnAllCatalogsFor(AfonCloud.AZURE).isCallingPolaris()).isTrue();
        assertThat(operations.checkPolarisAnswers(AfonCloud.AZURE).isCallingPolaris()).isFalse();
        assertThat(operations.configureOidcUsersFor(AfonCloud.AZURE).isCallingPolaris()).isFalse();
    }

    @Test
    void checkingKeyCloakAnswersSaysToSetUpTheKeyCloakStackOfTheCloudOrOfAnotherIfItHasNone() throws Throwable {
        properties.setValue(AfonProperty.OIDC_DOMAIN_NAME, "oauth.example");

        for (AfonCloud cloud : AfonCloud.values()) {
            operations.checkKeyCloakAnswers(cloud).getFunction().run();
        }

        assertThat(calls).hasSize(3).allSatisfy(call -> assertThat(call).startsWith(
            "check https://oauth.example/realms/master/.well-known/openid-configuration: Deploy the KeyCloak stack at OIDC_DOMAIN_NAME first"))
                         .satisfies(c -> assertThat(String.join("\n", c)).contains(
                             "manage-stack setup aws-keycloak", "manage-stack setup azure-keycloak", "manage-stack setup gcp-keycloak"));
    }

    @Test
    void doesNotValidateStackWithoutTfStateBeforeDeletingIt() {
        // a deployed stack would be synthesised, which needs the stack's config, so this passes only if it is not
        operations.validatePlanBeforeDeleting(StackId.parse("gcp-polaris"), cdktfDir.resolve("terraform.gcp-polaris.tfstate"));

        assertThat(calls).isEmpty();
    }

    @Test
    void doesNotValidateDeployedStackWithoutDefinitionBeforeDeletingIt() throws IOException {
        Path tfState = Files.createFile(cdktfDir.resolve("terraform.aws-nosuchstack.tfstate"));

        operations.validatePlanBeforeDeleting(StackId.parse("aws-nosuchstack"), tfState);

        assertThat(calls).isEmpty();
        assertThat(tfState).exists();
    }

    @Test
    void validatesDeployedStackAgainstTheConfigOfTheOperationsBeforeDeletingIt() throws IOException {
        Path tfState = Files.createFile(cdktfDir.resolve("terraform.aws-keycloak.tfstate"));

        operations.validatePlanBeforeDeleting(StackId.parse("aws-keycloak"), tfState);

        assertThat(calls).containsExactly("synthesise aws-keycloak with the config of the operations");
    }

    @Test
    void cannotDeleteDeployedStackThatFailsToValidate() throws IOException {
        Path tfState = Files.createFile(cdktfDir.resolve("terraform.aws-keycloak.tfstate"));
        synthesisFailure = new IllegalArgumentException("Property X not found");

        assertThatThrownBy(() -> operations.validatePlanBeforeDeleting(StackId.parse("aws-keycloak"), tfState))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot delete stack aws-keycloak")
            .hasMessageContaining("Property X not found")
            .hasMessageContaining("afon-cli.sh validate-stack aws-keycloak")
            .hasCause(synthesisFailure);
    }

    @Test
    void deploysStackFromAPlanSynthesisedFromTheConfigOfTheOperations() throws Exception {
        operations.deployStack(StackId.parse("gcp-polaris")).getFunction().run();

        assertThat(calls).containsExactly("synthesise gcp-polaris with the config of the operations", "start gcp-polaris");
    }
}
