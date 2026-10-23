package health.emrys.afon.cdk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerImagePinnerTest {

    private static final String OLD = "sha256:" + "a".repeat(64);
    private static final String NEW = "sha256:" + "b".repeat(64);

    private static final String YAML = """
        # the images
        images:
          # first
          keycloak:
            image: quay.io/keycloak/keycloak
            tag: 26.7.0
            digest: %s
            category: upstream
          curl:
            # no digest yet
            image: curlimages/curl
            tag: 8.11.1
          polaris:
            image: apache/polaris
            tag: ${polaris.version}
            category: upstream
        """.formatted(OLD);

    private final List<String> looked = new ArrayList<>();
    private final DockerImagePinner.DigestResolver resolver = reference -> {
        looked.add(reference);
        return NEW;
    };
    private Path file;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        file = Files.writeString(dir.resolve("docker-images.yaml"), YAML);
    }

    private List<DockerImagePinner.Change> pin(String... names) {
        return DockerImagePinner.pin(file, List.of(names), resolver, key -> "1.2.3");
    }

    @Test
    void refreshesTheDigestOfTheImagesThatHaveOneWhenNoneIsNamed() throws IOException {
        var changes = pin();
        assertThat(changes).containsExactly(new DockerImagePinner.Change("keycloak", "quay.io/keycloak/keycloak:26.7.0", OLD, NEW));
        assertThat(looked).containsExactly("quay.io/keycloak/keycloak:26.7.0");
        assertThat(Files.readString(file)).isEqualTo(YAML.replace(OLD, NEW));
    }

    @Test
    void addsTheDigestAfterTheTagOfANamedImageThatHasNone() throws IOException {
        assertThat(pin("curl")).containsExactly(new DockerImagePinner.Change("curl", "curlimages/curl:8.11.1", null, NEW));
        assertThat(Files.readString(file)).isEqualTo(YAML.replace("    tag: 8.11.1\n", "    tag: 8.11.1\n    digest: " + NEW + "\n"));
    }

    @Test
    void leavesTheFileAsItIsWhenTheDigestIsTheSame() throws IOException {
        file = Files.writeString(file, YAML.replace(OLD, NEW));
        var changes = pin();
        assertThat(changes).singleElement().satisfies(change -> assertThat(change.isChanged()).isFalse());
        assertThat(Files.readString(file)).isEqualTo(YAML.replace(OLD, NEW));
    }

    @Test
    void doesNotPinAnImageWhoseTagFollowsSomethingElse() throws IOException {
        assertThatThrownBy(() -> pin("polaris")).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("polaris").hasMessageContaining("unpinned");
        assertThat(Files.readString(file)).isEqualTo(YAML);
    }

    @Test
    void doesNotPinAnImageThatSaysWhyItIsNotPinned() throws IOException {
        String yaml = YAML.replace("    tag: 8.11.1\n", "    tag: 8.11.1\n    unpinned: chart image\n");
        file = Files.writeString(file, yaml);
        assertThatThrownBy(() -> pin("curl")).hasMessageContaining("curl is unpinned: chart image");
        assertThat(Files.readString(file)).isEqualTo(yaml);
    }

    @Test
    void rejectsAnUnknownImageAndWritesNothingWhenALookupFails() throws IOException {
        assertThatThrownBy(() -> pin("nope")).isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown image 'nope'");
        assertThatThrownBy(() -> DockerImagePinner.pin(file, List.of("keycloak", "curl"), reference -> {
            if (reference.contains("curl")) {
                throw new IOException("registry unreachable");
            }
            return NEW;
        }, key -> "1.2.3")).hasMessageContaining("registry unreachable");
        assertThat(Files.readString(file)).isEqualTo(YAML);
    }

    private java.util.Map<String, DockerImageDefinitions.Definition> images() {
        return DockerImageDefinitions.load(file, key -> "1.2.3");
    }

    @Test
    void verifyChecksTheImagesThatHaveADigestAndWritesNothing() throws IOException {
        var checks = DockerImagePinner.verify(images(), List.of(), reference -> OLD);
        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.name()).isEqualTo("keycloak");
            assertThat(check.isOk()).isTrue();
        });
        checks = DockerImagePinner.verify(images(), List.of(), resolver);
        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.isOk()).isFalse();
            assertThat(check.pinned()).isEqualTo(OLD);
            assertThat(check.registry()).isEqualTo(NEW);
        });
        assertThat(Files.readString(file)).isEqualTo(YAML);
    }

    @Test
    void verifyFailsANamedImageWithNoDigestAndReportsLookupErrorsPerImage() {
        assertThat(DockerImagePinner.verify(images(), List.of("curl"), resolver)).singleElement()
            .satisfies(check -> assertThat(check.isOk()).isFalse());
        assertThat(DockerImagePinner.verify(images(), List.of("keycloak", "curl"), reference -> {
            throw new IOException("registry unreachable");
        })).hasSize(2).allSatisfy(check -> {
            assertThat(check.isOk()).isFalse();
            assertThat(check.error()).isEqualTo("registry unreachable");
        });
        assertThatThrownBy(() -> DockerImagePinner.verify(images(), List.of("nope"), resolver))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown image 'nope'");
    }
}
