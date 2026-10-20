package health.emrys.afon.ops.scripts;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackRole;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.AfonProperty.AWS_KEYCLOAK_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackNeedsTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    @Test
    void roleFindsTheStackOfTheCloudWithIt() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        StackNeeds needs = StackNeeds.resolve(AWS, "tre", properties);

        assertThat(needs.id()).hasToString("aws-full");
        assertThat(needs.role()).isEqualTo(StackRole.TRE);
        assertThat(needs.domains()).containsExactly("OIDC_DOMAIN_NAME", "AWS_POLARIS_DOMAIN_NAME", "AWS_JUPYTER_DOMAIN_NAME");
        assertThat(needs.components()).startsWith("Keycloak", "Polaris", "Jupyter").contains("Git", "Logging");
    }

    @Test
    void roleMayBeGivenAsCloudAndRoleLikeTheOtherCommandsTakeIt() {
        assertThat(StackNeeds.resolve(AWS, "aws-tre", properties).id()).hasToString("aws-full");
        assertThat(StackNeeds.resolve(AZURE, "AZURE-Catalog", properties).id()).hasToString("azure-polaris");
        assertThatThrownBy(() -> StackNeeds.resolve(AWS, "azure-tre", properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Stack azure-full is not a stack of aws");
    }

    @Test
    void theRoleMayBeInAnyCase() {
        assertThat(StackNeeds.resolve(AZURE, "TRE", properties).id()).hasToString("azure-full");
        assertThat(StackNeeds.resolve(AZURE, "Catalog", properties).id()).hasToString("azure-polaris");
    }

    @Test
    void whatTheStackNeedsDependsOnWhatItDeploysForTheConfig() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        StackNeeds needs = StackNeeds.resolve(AWS, "tre", properties);

        // it uses the KeyCloak of another stack, so has no DNS zone for it
        assertThat(needs.domains()).containsExactly("AWS_POLARIS_DOMAIN_NAME", "AWS_JUPYTER_DOMAIN_NAME");
        assertThat(needs.components()).doesNotContain("Keycloak");
    }

    @Test
    void theRolesOfTheBundledStacksNeedTheirOwnDomains() {
        assertThat(StackNeeds.resolve(AWS, "identity", properties).domains()).containsExactly("OIDC_DOMAIN_NAME");
        assertThat(StackNeeds.resolve(AZURE, "catalog", properties).domains()).containsExactly("AZURE_POLARIS_DOMAIN_NAME");
        assertThat(StackNeeds.resolve(GCP, "catalog", properties).components()).containsExactly("Polaris");
    }

    @Test
    void idFindsAStackWhetherOrNotItHasARole() {
        StackNeeds needs = StackNeeds.resolve(AWS, "aws-lab", properties);

        assertThat(needs.id()).hasToString("aws-lab");
        assertThat(needs.role()).isNull();
        assertThat(needs.components()).contains("Jupyter", "Spark").doesNotContain("Git");
        assertThat(StackNeeds.resolve(AWS, "AWS-Full", properties).id()).hasToString("aws-full");
    }

    @Test
    void envGivesWhatTheStackNeedsToAScript() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        Map<String, String> env = StackNeeds.resolve(AWS, "tre", properties).env();

        assertThat(env).containsEntry("AFON_STACK_ID", "aws-full").containsEntry("AFON_STACK_ROLE", "tre")
                       .containsEntry("AFON_STACK_DOMAINS", "OIDC_DOMAIN_NAME AWS_POLARIS_DOMAIN_NAME AWS_JUPYTER_DOMAIN_NAME");
        assertThat(" " + env.get("AFON_STACK_COMPONENTS") + " ").startsWith(" Keycloak Polaris Jupyter ").contains(" Git ");
        assertThat(StackNeeds.resolve(AWS, "aws-lab", properties).env()).containsEntry("AFON_STACK_ROLE", "");
    }

    @Test
    void rejectsWhatIsNotAStackOfTheCloudInTheStacksFile() {
        assertThatThrownBy(() -> StackNeeds.resolve(GCP, "tre", properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No stack of gcp has the role 'tre' in the stacks of ");
        assertThatThrownBy(() -> StackNeeds.resolve(AWS, "azure-full", properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Stack azure-full is not a stack of aws");
        assertThatThrownBy(() -> StackNeeds.resolve(AWS, "aws-nonesuch", properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No stack 'aws-nonesuch' in the stacks of ");
        assertThatThrownBy(() -> StackNeeds.resolve(AWS, "nonsense", properties)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scriptMainFindsTheStackAmongItsArgsInAnyOrderAndPassesItsId() {
        Map<String, String> env = new LinkedHashMap<>();

        assertThat(ScriptMain.withStackResolved(AZURE, List.of("true", "tre"), properties, env))
            .containsExactly("true", "azure-full");
        assertThat(env).containsEntry("AFON_STACK_ID", "azure-full");
        assertThat(ScriptMain.withStackResolved(AZURE, List.of("catalog", "false"), properties, new LinkedHashMap<>()))
            .containsExactly("azure-polaris", "false");
        assertThat(ScriptMain.withStackResolved(AWS, List.of("aws-lab"), properties, new LinkedHashMap<>()))
            .containsExactly("aws-lab");
    }

    @Test
    void scriptMainLeavesAScriptWithNoStackToReportIt() {
        Map<String, String> env = new LinkedHashMap<>();

        assertThat(ScriptMain.withStackResolved(AZURE, List.of("true"), properties, env)).containsExactly("true");
        assertThat(ScriptMain.withStackResolved(AWS, List.of(), properties, env)).isEmpty();
        assertThat(env).isEmpty();
        assertThatThrownBy(() -> ScriptMain.withStackResolved(AZURE, List.of("true", "mars"), properties, env))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stacksServingAComponentAreThoseOfTheCloudThatDeployItForTheConfig() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        assertThat(StackNeeds.serving(AWS, "Keycloak", properties).stream().map(Object::toString))
            .containsExactly("aws-full", "aws-keycloak", "aws-lab", "aws-snowflake");
        assertThat(StackNeeds.serving(AWS, "Polaris", properties).stream().map(Object::toString))
            .containsExactly("aws-full", "aws-lab", "aws-polaris", "aws-snowflake");
        assertThat(StackNeeds.serving(AZURE, "Polaris", properties).stream().map(Object::toString))
            .containsExactly("azure-full", "azure-polaris");

        // the stacks with their own KeyCloak use another's, so no longer serve it
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(StackNeeds.serving(AWS, "Keycloak", properties).stream().map(Object::toString))
            .containsExactly("aws-keycloak");
        assertThat(StackNeeds.serving(GCP, "Keycloak", properties).stream().map(Object::toString))
            .containsExactly("gcp-keycloak");
        assertThatThrownBy(() -> StackNeeds.serving(AWS, "Nonesuch", properties))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown component 'Nonesuch'");
    }

    @Test
    void scriptMainGivesTheStacksServingAComponentOnlyWhenNoStackIsGiven() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        Map<String, String> none = new LinkedHashMap<>();
        assertThat(ScriptMain.withStackResolved(AWS, List.of(), properties, none, "Keycloak")).isEmpty();
        assertThat(none).containsOnlyKeys("AFON_STACK_CANDIDATES").containsEntry("AFON_STACK_CANDIDATES", "aws-keycloak");

        // a stack is given, by its role: that is the one
        Map<String, String> given = new LinkedHashMap<>();
        assertThat(ScriptMain.withStackResolved(AWS, List.of("identity"), properties, given, "Keycloak"))
            .containsExactly("aws-keycloak");
        assertThat(given).containsEntry("AFON_STACK_ID", "aws-keycloak").doesNotContainKey("AFON_STACK_CANDIDATES");

        // not for a script that serves no component
        Map<String, String> plain = new LinkedHashMap<>();
        ScriptMain.withStackResolved(AWS, List.of(), properties, plain, null);
        assertThat(plain).isEmpty();
    }
}
