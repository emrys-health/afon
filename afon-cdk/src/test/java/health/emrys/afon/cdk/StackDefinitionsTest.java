package health.emrys.afon.cdk;

import health.emrys.afon.cdk.components.*;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import health.emrys.afon.config.StackRole;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static health.emrys.afon.config.AfonCloud.*;
import static health.emrys.afon.config.AfonProperty.AWS_KEYCLOAK_EXTERNAL;
import static health.emrys.afon.config.AfonProperty.AZURE_KEYCLOAK_EXTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackDefinitionsTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    private List<Class<?>> componentsOf(String stackId) {
        return StackDefinitions.get(StackId.parse(stackId)).components().apply(properties)
                               .stream().<Class<?>>map(StackComponent::getClass).toList();
    }

    @Test
    void stacksExtendOneAnotherByComponents() {
        assertThat(componentsOf("aws-keycloak")).containsExactly(KeycloakComponent.class);
        assertThat(componentsOf("aws-polaris")).containsExactly(PolarisComponent.class);
        assertThat(componentsOf("aws-full")).contains(JupyterComponent.class, SparkComponent.class);
    }

    @Test
    void snowflakeStackIsTheFullStackQueriedBySnowflake() {
        assertThat(componentsOf("aws-snowflake")).startsWith(componentsOf("aws-full").toArray(new Class<?>[0]))
            .hasSize(componentsOf("aws-full").size() + 1).endsWith(SnowflakeComponent.class);
    }

    @Test
    void awsStacksDeployOwnKeyCloakUnlessExternal() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        assertThat(componentsOf("aws-full")).contains(KeycloakComponent.class);
        assertThat(componentsOf("aws-full").indexOf(KeycloakComponent.class))
            .isLessThan(componentsOf("aws-full").indexOf(PolarisComponent.class));
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(componentsOf("aws-full")).doesNotContain(KeycloakComponent.class);
    }

    @Test
    void componentsAreNewForEachStackBuilt() {
        StackDefinition definition = StackDefinitions.get(StackId.parse("azure-full"));
        assertThat(definition.components().apply(properties).get(0))
            .isNotSameAs(definition.components().apply(properties).get(0));
        // the components of a cloud's full stack are the same, as each adapts to the cloud it is deployed to
        assertThat(componentsOf("azure-full")).containsExactlyInAnyOrderElementsOf(componentsOf("aws-full"));
    }

    @Test
    void azureStacksDeployOwnKeyCloakUnlessExternal() {
        assertThat(componentsOf("azure-keycloak")).containsExactly(KeycloakComponent.class);
        properties.setValue(AZURE_KEYCLOAK_EXTERNAL, "false");
        assertThat(componentsOf("azure-full")).contains(KeycloakComponent.class, JupyterComponent.class);
        assertThat(componentsOf("azure-full").indexOf(KeycloakComponent.class))
            .isLessThan(componentsOf("azure-full").indexOf(PolarisComponent.class));
        properties.setValue(AZURE_KEYCLOAK_EXTERNAL, "true");
        assertThat(componentsOf("azure-full")).doesNotContain(KeycloakComponent.class).contains(JupyterComponent.class);
    }

    @Test
    void eachCloudHasItsOwnKeyCloakSwitch() {
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        properties.setValue(AZURE_KEYCLOAK_EXTERNAL, "false");
        assertThat(componentsOf("aws-full")).doesNotContain(KeycloakComponent.class);
        assertThat(componentsOf("azure-full")).contains(KeycloakComponent.class);
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        properties.setValue(AZURE_KEYCLOAK_EXTERNAL, "true");
        assertThat(componentsOf("aws-full")).contains(KeycloakComponent.class);
        assertThat(componentsOf("azure-full")).doesNotContain(KeycloakComponent.class);
    }

    @Test
    void onlyKeycloakStacksDoNotPinEgressIp() {
        StackDefinitions.all().forEach(definition ->
            assertThat(definition.pinnedEgressIp()).as(definition.id().toString())
                                                   .isEqualTo(!definition.id().type().equals("keycloak")));
        assertThat(StackDefinitions.find(new StackId(AZURE, "full"))).isPresent();
    }

    @Test
    void cannotGetUnknownStack() {
        assertThatThrownBy(() -> StackDefinitions.get(StackId.parse("gcp-full")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("No such stack: gcp-full");
    }

    private static List<String> namesOf(Map<StackId, StackDefinition> definitions, String stackId, AfonPropertyProvider properties) {
        return definitions.get(StackId.parse(stackId)).componentsFor(properties).stream()
                          .map(component -> component.getClass().getSimpleName()).toList();
    }

    @Test
    void parsesStacksExtendingOthersWithConditionalComponents() {
        Map<StackId, StackDefinition> definitions = StackDefinitions.parse("""
            stacks:
              aws-base:
                pinnedEgressIp: false
                components:
                  - name: Keycloak
                    unless: KEYCLOAK_EXTERNAL
                  - Polaris
              aws-more:
                extends: aws-base
                components: [Jupyter]
              azure-more:
                components:
                  - Polaris
                  - Jupyter
                  - name: Trino
                    when: KEYCLOAK_EXTERNAL
            """, "test");
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        properties.setValue(AZURE_KEYCLOAK_EXTERNAL, "true");
        assertThat(namesOf(definitions, "aws-more", properties))
            .containsExactly("KeycloakComponent", "PolarisComponent", "JupyterComponent");
        assertThat(namesOf(definitions, "azure-more", properties))
            .containsExactly("PolarisComponent", "JupyterComponent", "TrinoComponent");
        assertThat(definitions.get(StackId.parse("aws-more")).pinnedEgressIp()).isFalse();
        assertThat(definitions.keySet()).extracting(StackId::toString).containsExactly("aws-base", "aws-more", "azure-more");
    }

    @Test
    void rejectsInvalidDefinitions() {
        assertInvalid("stacks:\n  aws-x:\n    components: [Nonesuch]", "unknown component 'Nonesuch'");
        assertInvalid("stacks:\n  aws-x:\n    extends: aws-y", "aws-x extends aws-y, which is not defined");
        assertInvalid("stacks:\n  azure-x:\n    extends: aws-y\n  aws-y:\n    components: [Polaris]", "different clouds are independent");
        assertInvalid("stacks:\n  aws-x:\n    extends: aws-y\n  aws-y:\n    extends: aws-x", "extends itself");
        assertInvalid("stacks:\n  aws-x:\n    component: [Polaris]", "unknown 'component'");
        assertInvalid("stacks:\n  mars-x:\n    components: [Polaris]", "unknown cloud 'mars'");
        assertInvalid("stacks:\n  aws-x:\n    components:\n      - name: Polaris\n        when: A\n        unless: B", "both when and unless");
        assertInvalid("stacks:\n  aws-x:\n    pinnedEgressIp: maybe", "true or false");
        assertInvalid("nothing: here", "stacks");
    }

    private void assertInvalid(String yaml, String message) {
        assertThatThrownBy(() -> StackDefinitions.parse(yaml, "bad.yaml"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad.yaml").hasMessageContaining(message);
    }

    @Test
    void everyComponentCanBeNamedInTheStacks() {
        try (ScanResult scan = new ClassGraph().enableClassInfo().acceptPackages("health.emrys.afon.cdk").scan()) {
            Set<String> expected = scan.getClassesImplementing(StackComponent.class.getName()).stream()
                .filter(info -> !info.isAbstract() && !info.isInterface() && info.getSimpleName().endsWith("Component"))
                .map(info -> info.getSimpleName().replaceFirst("Component$", "")).collect(Collectors.toSet());
            assertThat(StackComponents.names()).containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void bundledStacksAreTheOnesDocumented() {
        assertThat(StackDefinitions.load(null).keySet()).extracting(StackId::toString)
            .containsExactly("aws-full", "aws-keycloak", "aws-polaris", "aws-snowflake", "azure-full", "azure-keycloak",
                             "azure-polaris", "gcp-polaris");
    }

    @Test
    void testStacksAreTheBundledOnesButForTheFullStacksHavingNoTrino() {
        Map<StackId, StackDefinition> bundled = StackDefinitions.load(null);
        // but for aws-lab, which they add
        assertThat(StackDefinitions.all()).extracting(definition -> definition.id().toString())
            .containsExactlyInAnyOrderElementsOf(Stream.concat(bundled.keySet().stream().map(StackId::toString), Stream.of("aws-lab")).toList());
        StackDefinitions.all().stream().filter(definition -> !definition.id().toString().equals("aws-lab")).forEach(definition -> {
            String id = definition.id().toString();
            List<String> expected = namesOf(bundled, id, properties);
            if (id.endsWith("-full") || id.equals("aws-snowflake")) {
                assertThat(expected).as(id + " bundled").contains("TrinoComponent");
                expected = expected.stream().filter(name -> !name.equals("TrinoComponent")).toList();
            }
            assertThat(componentNames(definition)).as(id).isEqualTo(expected);
            assertThat(definition.pinnedEgressIp()).as(id).isEqualTo(bundled.get(definition.id()).pinnedEgressIp());
        });
    }

    @Test
    void stackCanBeAddedWithNoChangeOfCode() {
        assertThat(StackDefinitions.find(StackId.parse("aws-lab"))).isPresent();
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "false");
        assertThat(componentsOf("aws-lab")).containsExactly(KeycloakComponent.class, PolarisComponent.class, JupyterComponent.class,
                                                          NetworkPoliciesComponent.class, SparkOperatorComponent.class,
                                                          SparkComponent.class, CrdCleanupComponent.class);
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(componentsOf("aws-lab")).doesNotContain(KeycloakComponent.class).contains(SparkComponent.class);
    }

    @Test
    void rejectsStacksDefinedMoreThanOnce() {
        assertInvalid("stacks:\n  aws-x:\n    components: [Polaris]\n  aws-x:\n    components: [Git]", "aws-x");
        assertInvalid("stacks:\n  aws-x:\n    components: [Polaris]\n  AWS-X:\n    components: [Git]", "aws-x is defined more than once");
        assertInvalid("stacks:\n  aws-all:\n    components: [Polaris]", "stands for every stack of the cloud");
        assertInvalid("stacks:\n  aws-x: [", "bad.yaml");
    }

    @Test
    void stacksOfACloudHaveNoComponentOfAnother() {
        // eg a component named for AWS casts the platform to the AWS one, so cannot be deployed to Azure
        for (StackDefinition definition : StackDefinitions.all()) {
            String cloud = definition.id().cloud().toString();
            definition.componentsFor(properties).forEach(component -> {
                String name = component.getClass().getSimpleName();
                for (String other : List.of("aws", "azure", "gcp")) {
                    if (!other.equals(cloud) && name.toLowerCase().startsWith(other)) {
                        throw new AssertionError(definition.id() + " has " + name + ", of the cloud " + other);
                    }
                }
            });
        }
    }

    @Test
    void compositionOfStacksCanBeChanged() {
        assertThat(componentsOf("aws-full")).doesNotContain(TrinoComponent.class).contains(SparkComponent.class);
        assertThat(componentsOf("azure-full")).doesNotContain(TrinoComponent.class);
        // aws-snowflake extends aws-full, so it follows
        assertThat(componentsOf("aws-snowflake")).doesNotContain(TrinoComponent.class).contains(SnowflakeComponent.class);
    }

    private List<String> componentNames(StackDefinition definition) {
        return definition.componentsFor(properties).stream().map(component -> component.getClass().getSimpleName()).toList();
    }

    @Test
    void userFileInConfigDirReplacesTheBundledStacks(@TempDir Path configDir) throws IOException {
        Path file = configDir.resolve(StackDefinitions.STACKS_FILE);
        Files.writeString(file, "stacks:\n  aws-mine:\n    components: [Polaris, Git]\n");
        Map<StackId, StackDefinition> definitions = StackDefinitions.load(file);
        assertThat(definitions.keySet()).extracting(StackId::toString).containsExactly("aws-mine");
        assertThat(namesOf(definitions, "aws-mine", properties)).containsExactly("PolarisComponent", "GitComponent");
        assertThat(StackDefinitions.load(null).keySet()).hasSizeGreaterThan(1);
    }

    @Test
    void bundledStacksCanBeExportedToTheConfigDirWithoutOverwriting(@TempDir Path dir) throws IOException {
        Path configDir = dir.resolve("config");
        Path file = StackDefinitions.exportBundled(configDir);
        assertThat(file).isEqualTo(configDir.resolve(StackDefinitions.STACKS_FILE));
        // what it exports is what is bundled, so the exported file defines the same stacks
        assertThat(StackDefinitions.load(file).keySet()).isEqualTo(StackDefinitions.load(null).keySet());
        Files.writeString(file, "mine");
        assertThatThrownBy(() -> StackDefinitions.exportBundled(configDir))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("exists already");
        assertThat(file).hasContent("mine");
    }

    @Test
    void stacksFileIsTheOneNamedElseThatOfTheConfigDirElseTheBundledOne(@TempDir Path dir) throws IOException {
        Path inConfigDir = Files.writeString(dir.resolve(StackDefinitions.STACKS_FILE), "stacks: {}");
        Path named = Files.writeString(dir.resolve("named.yaml"), "stacks: {}");
        String configDir = dir.toString();
        assertThat(StackDefinitions.stacksFile(null, configDir)).isEqualTo(inConfigDir);
        assertThat(StackDefinitions.stacksFile(" ", configDir)).isEqualTo(inConfigDir);
        assertThat(StackDefinitions.stacksFile(named.toString(), configDir)).isEqualTo(named);
        // only the bundled ones whatever the config dir has, as for the build
        assertThat(StackDefinitions.stacksFile(StackDefinitions.BUNDLED, configDir)).isNull();
        assertThat(StackDefinitions.stacksFile(null, dir.resolve("none").toString())).isNull();
        assertThatThrownBy(() -> StackDefinitions.stacksFile(dir.resolve("none.yaml").toString(), configDir))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("none.yaml");
    }

    @Test
    void invalidUserFileFailsNamingIt(@TempDir Path configDir) throws IOException {
        Path file = configDir.resolve(StackDefinitions.STACKS_FILE);
        Files.writeString(file, "stacks:\n  aws-mine:\n    components: [Nonesuch]\n");
        assertThatThrownBy(() -> StackDefinitions.load(file))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(file.toString())
            .hasMessageContaining("unknown component 'Nonesuch'");
    }

    @Test
    void stacksAreFoundByTheirRoleAndTheComponentsTheyDeclare() {
        assertThat(StackDefinitions.withRole(AWS, StackRole.TRE)).map(definition -> definition.id().toString()).contains("aws-full");
        assertThat(StackDefinitions.withRole(AWS, StackRole.IDENTITY)).map(definition -> definition.id().toString()).contains("aws-keycloak");
        assertThat(StackDefinitions.withRole(AZURE, StackRole.CATALOG)).map(definition -> definition.id().toString()).contains("azure-polaris");
        assertThat(StackDefinitions.withRole(GCP, StackRole.IDENTITY)).isEmpty();
        // a stack that extends another does not take its role
        assertThat(StackDefinitions.get(StackId.parse("aws-snowflake")).role()).isNull();
        assertThat(StackDefinitions.declaring(AWS, SnowflakeComponent.class)).map(definition -> definition.id().toString())
                                                                               .contains("aws-snowflake");
        assertThat(StackDefinitions.declaring(AZURE, SnowflakeComponent.class)).isEmpty();
        // declared is what the definition lists, whatever the config leaves out
        properties.setValue(AWS_KEYCLOAK_EXTERNAL, "true");
        assertThat(StackDefinitions.get(StackId.parse("aws-full")).declares(KeycloakComponent.class)).isTrue();
        assertThat(componentsOf("aws-full")).doesNotContain(KeycloakComponent.class);
    }

    @Test
    void parsesTheRoleOfAStackAndRejectsUnknownOrRepeatedOnes() {
        Map<StackId, StackDefinition> definitions = StackDefinitions.parse(
            "stacks:\n  aws-a:\n    role: tre\n    components: [Polaris]\n  azure-a:\n    role: tre\n    components: [Polaris]\n"
            + "  aws-b:\n    extends: aws-a\n    components: [Git]\n", "test");
        assertThat(definitions.get(StackId.parse("aws-a")).role()).isEqualTo(StackRole.TRE);
        assertThat(definitions.get(StackId.parse("azure-a")).role()).isEqualTo(StackRole.TRE);
        assertThat(definitions.get(StackId.parse("aws-b")).role()).isNull();
        assertInvalid("stacks:\n  aws-x:\n    role: boss\n    components: [Polaris]", "unknown role 'boss'");
        assertInvalid("stacks:\n  aws-x:\n    role: tre\n  aws-y:\n    role: tre", "aws-x and aws-y both have the role tre");
    }

    @Test
    void bundledAndTestStacksHaveTheSameRoles() {
        Map<StackId, StackDefinition> bundled = StackDefinitions.load(null);
        StackDefinitions.all().forEach(definition -> {
            if (bundled.containsKey(definition.id())) {
                assertThat(definition.role()).as(definition.id().toString()).isEqualTo(bundled.get(definition.id()).role());
            }
        });
    }

    @Test
    void stackIsNamedByItsIdOrByItsRoleInTheCloud() {
        assertThat(StackDefinitions.resolve(StackId.parse("aws-tre"))).hasToString("aws-full");
        assertThat(StackDefinitions.resolve(StackId.parse("AZURE-Catalog"))).hasToString("azure-polaris");
        assertThat(StackDefinitions.resolve(StackId.parse("aws-identity"))).hasToString("aws-keycloak");
        // a stack named by its id is itself, with or without a role
        assertThat(StackDefinitions.resolve(StackId.parse("aws-full"))).hasToString("aws-full");
        assertThat(StackDefinitions.resolve(StackId.parse("aws-lab"))).hasToString("aws-lab");
        // otherwise as it is, for the caller to report: no stack of the cloud has the role, or it is not a role or a stack
        assertThat(StackDefinitions.resolve(StackId.parse("gcp-identity"))).hasToString("gcp-identity");
        assertThat(StackDefinitions.resolve(StackId.parse("aws-nonesuch"))).hasToString("aws-nonesuch");
        assertThat(StackDefinitions.resolve(StackId.all(AWS))).isEqualTo(StackId.all(AWS));
    }

    @Test
    void aStackDefinedUnderTheIdIsNotMistakenForTheOneWithTheRole(@TempDir Path dir) throws IOException {
        String before = System.getProperty(StackDefinitions.STACKS_PROPERTY);
        System.setProperty(StackDefinitions.STACKS_PROPERTY, Files.writeString(dir.resolve("mixed.yaml"), """
            stacks:
              aws-tre:
                role: catalog
                components: [Polaris]
              aws-big:
                role: tre
                components: [Polaris, Git]
            """).toString());
        try {
            assertThat(StackDefinitions.resolve(StackId.parse("aws-tre"))).hasToString("aws-tre");
            assertThat(StackDefinitions.resolve(StackId.parse("aws-catalog"))).hasToString("aws-tre");
            assertThat(StackDefinitions.resolve(StackId.parse("aws-big"))).hasToString("aws-big");
        } finally {
            if (before == null) {
                System.clearProperty(StackDefinitions.STACKS_PROPERTY);
            } else {
                System.setProperty(StackDefinitions.STACKS_PROPERTY, before);
            }
        }
    }
}
