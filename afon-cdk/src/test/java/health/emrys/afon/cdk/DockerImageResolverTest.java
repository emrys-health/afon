package health.emrys.afon.cdk;

import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The images are referenced in the registry of the cloud, where {@code docker-image --sign} copied and signed them, when it has one.
 */
class DockerImageResolverTest {

    private static final String REGISTRY = "r.example.io/emrys";
    private static final String FIRST_PARTY_DIGEST     = "sha256:" + "a".repeat(64);
    private static final String UPSTREAM_DIGEST   = "sha256:" + "b".repeat(64);
    /** which the gcp stacks need, for Loki to write its logs to the bucket */
    private static final Map<String, String> GCP_CREDENTIALS = Map.of(
        "GOOGLE_APPLICATION_CREDENTIALS", "{\"type\":\"service_account\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n\"}");

    private static Map<String, DockerImageDefinitions.Definition> images() {
        Map<String, DockerImageDefinitions.Definition> images = new LinkedHashMap<>();
        images.put("spark", new DockerImageDefinitions.Definition("spark", "emryshealth/spark", "4.0.1", FIRST_PARTY_DIGEST, "first-party", null));
        images.put("keycloak", new DockerImageDefinitions.Definition("keycloak", "quay.io/keycloak/keycloak", "26", UPSTREAM_DIGEST, "upstream", null));
        images.put("postgres", new DockerImageDefinitions.Definition("postgres", "postgres", "16", UPSTREAM_DIGEST, "third-party", null));
        images.put("trino", new DockerImageDefinitions.Definition("trino", "trinodb/trino", "481", null, "upstream", "chart image"));
        return images;
    }

    @Test
    void withoutARegistryAnImageIsReferencedAsDefined() {
        for (String registry : new String[]{null, "", "  "}) {
            var resolver = new DockerImageResolver(images(), registry);
            assertThat(resolver.reference("keycloak")).isEqualTo("quay.io/keycloak/keycloak:26@" + UPSTREAM_DIGEST);
            assertThat(resolver.reference("spark")).isEqualTo("emryshealth/spark:4.0.1@" + FIRST_PARTY_DIGEST);
            assertThat(resolver.reference("trino")).isEqualTo("trinodb/trino:481");
        }
    }

    @Test
    void withARegistryTheImagesThatAreNotFirstPartyAreReferencedInItAtTheirTagAndDigest() {
        var resolver = new DockerImageResolver(images(), REGISTRY);

        assertThat(resolver.reference("keycloak")).isEqualTo(REGISTRY + "/keycloak/keycloak:26@" + UPSTREAM_DIGEST);
        assertThat(resolver.reference("postgres")).isEqualTo(REGISTRY + "/postgres:16@" + UPSTREAM_DIGEST);
    }

    @Test
    void firstPartyImagesAndThoseThatCannotBeSignedStayWhereTheyAre() {
        var resolver = new DockerImageResolver(images(), REGISTRY);

        assertThat(resolver.reference("spark")).as("signed where it is").isEqualTo("emryshealth/spark:4.0.1@" + FIRST_PARTY_DIGEST);
        assertThat(resolver.reference("trino")).as("no digest, so not mirrored").isEqualTo("trinodb/trino:481");
    }

    @Test
    void theTagOfAnImageIsThatOfItsDefinitionWhateverTheRegistry() {
        assertThat(new DockerImageResolver(images(), REGISTRY).tag("trino")).isEqualTo("481");
        assertThat(new DockerImageResolver(images(), "").tag("keycloak")).isEqualTo("26");
        assertThatThrownBy(() -> new DockerImageResolver(images(), REGISTRY).tag("nope")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTrailingSlashOfTheRegistryIsIgnored() {
        assertThat(new DockerImageResolver(images(), " " + REGISTRY + "/ ").reference("postgres")).isEqualTo(REGISTRY + "/postgres:16@" + UPSTREAM_DIGEST);
    }

    @Test
    void anUnknownImageFailsNamingTheOnesThereAre() {
        assertThatThrownBy(() -> new DockerImageResolver(images(), REGISTRY).reference("nope"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("No Docker image 'nope'").hasMessageContaining("keycloak");
    }

    @Test
    void theReferenceDeployedIsTheImageThatSigningSigned() throws IOException {
        var images   = DockerImages.all();
        var resolver = new DockerImageResolver(images, REGISTRY);
        var signed   = DockerImageSigner.sign(images, List.of(), REGISTRY, "key", command -> {
        }, reference -> images.values().stream().filter(image -> reference.startsWith(image.repository(REGISTRY) + ":"))
                              .findFirst().orElseThrow().digest());

        assertThat(signed).isNotEmpty().allSatisfy(done -> {
            String deployed = resolver.reference(done.name());
            assertThat(deployed).as(done.name()).endsWith(done.signed().substring(done.signed().indexOf('@')))
                                .startsWith(done.signed().substring(0, done.signed().indexOf('@')) + ":");
        });
        assertThat(images.values()).filteredOn(image -> signed.stream().noneMatch(done -> done.name().equals(image.name())))
                                   .as("images that are not signed, so are deployed as they are")
                                   .allSatisfy(image -> assertThat(resolver.reference(image.name())).isEqualTo(image.reference()));
    }

    /**
     * @return whether the plan refers to the repository of the image as it is outside the registry, ie not as the tail of the path of its copy
     */
    private static boolean refersToUpstream(String plan, DockerImageDefinitions.Definition image) {
        return Pattern.compile("(?<![\\w./-])" + Pattern.quote(image.image() + ":" + image.tag())).matcher(plan).find();
    }

    @Test
    void aStackDeploysTheCopiesInTheRegistryOfItsCloud(@TempDir Path dir) throws Exception {
        var mirrored = DockerImages.all().values().stream().filter(image -> image.isMirrored(REGISTRY)).toList();
        var firstParty     = DockerImages.all().values().stream().filter(image -> DockerImageDefinitions.FIRST_PARTY.equals(image.category())).toList();
        assertThat(mirrored).as("images that are mirrored").isNotEmpty();
        assertThat(firstParty).as("images that are first-party").isNotEmpty();
        List<String> problems = new ArrayList<>();
        List<String> deployed = new ArrayList<>();

        for (String cloud : List.of("aws", "azure", "gcp")) {
            String stack = cloud + "-full";
            String plan  = SynthesisSupport.synthesise(StackId.parse(stack), dir.resolve(stack),
                                                       merged(Map.of(cloud.toUpperCase() + "_IMAGE_REGISTRY", REGISTRY), GCP_CREDENTIALS));
            for (var image : mirrored) {
                if (refersToUpstream(plan, image)) {
                    problems.add(stack + " still pulls " + image.name() + " from " + image.image());
                }
                if (plan.contains(image.reference(REGISTRY))) {
                    deployed.add(stack + " " + image.name());
                }
            }
            for (var image : firstParty) {
                if (plan.contains(REGISTRY + "/" + image.image())) {
                    problems.add(stack + " pulls " + image.name() + ", which is first-party, from the registry");
                }
            }
        }

        assertThat(problems).as("see DockerImageResolver").isEmpty();
        assertThat(deployed).as("images deployed from the registry")
                            .contains("aws-full keycloak", "aws-full polaris", "azure-full keycloak", "azure-full polaris",
                                      "gcp-full keycloak", "gcp-full polaris", "aws-full postgres-alpine");
    }

    @Test
    void aStackDeploysTheImagesAsDefinedWithoutARegistry(@TempDir Path dir) throws Exception {
        String plan = SynthesisSupport.synthesise(StackId.parse("aws-full"), dir, Map.of());

        assertThat(plan).contains(DockerImages.KEYCLOAK_IMAGE, DockerImages.POLARIS_IMAGE, DockerImages.POSTGRES_ALPINE_IMAGE)
                        .doesNotContain(REGISTRY);
    }

    @Test
    void aStackDoesNotUseTheRegistryOfAnotherCloud(@TempDir Path dir) throws Exception {
        String plan = SynthesisSupport.synthesise(StackId.parse("aws-full"), dir, Map.of("AZURE_IMAGE_REGISTRY", REGISTRY));

        assertThat(plan).contains(DockerImages.KEYCLOAK_IMAGE).doesNotContain(REGISTRY);
    }

    private static Map<String, String> merged(Map<String, String> first, Map<String, String> second) {
        Map<String, String> all = new LinkedHashMap<>(first);
        all.putAll(second);
        return all;
    }
}
