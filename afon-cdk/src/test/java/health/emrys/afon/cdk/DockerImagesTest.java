package health.emrys.afon.cdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerImagesTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private static Map<String, DockerImageDefinitions.Definition> parse(String yaml) {
        return DockerImageDefinitions.parse(yaml, "test.yaml", key -> "polaris.version".equals(key) ? "1.2.3" : null);
    }

    @Test
    void bundledImagesKeepTheirReferences() {
        assertThat(DockerImages.KEYCLOAK_IMAGE).startsWith("quay.io/keycloak/keycloak:26.6.3@sha256:");
        assertThat(DockerImages.SPARK_IMAGE).startsWith("ph1ll1phenry/spark_jars_cp:4.0.1@sha256:");
        assertThat(DockerImages.POSTGRES_ALPINE_IMAGE).startsWith("postgres:16-alpine@sha256:");
        assertThat(DockerImages.TRINO_IMAGE_TAG).isEqualTo("481");
        assertThat(DockerImages.GITEA_IMAGE).startsWith("docker.gitea.com/gitea:1.27.0-rootless@sha256:");
        assertThat(DockerImages.POLARIS_IMAGE).isEqualTo("apache/polaris:" + DockerImages.POLARIS_VERSION);
        assertThat(DockerImages.POLARIS_ADMIN_TOOL_IMAGE).isEqualTo("apache/polaris-admin-tool:" + DockerImages.POLARIS_VERSION);
    }

    @Test
    void imagesThatTheirTagFollowsAreNotPinned() {
        // polaris is the version the project builds against, which a digest of another version would silently outlive
        assertThat(DockerImages.POLARIS_IMAGE).isEqualTo("apache/polaris:" + DockerImages.POLARIS_VERSION);
        assertThat(DockerImages.all().get("trino").digest()).isNull();
        assertThat(DockerImages.all().values()).filteredOn(image -> image.digest() == null)
            .extracting(DockerImageDefinitions.Definition::name).containsExactlyInAnyOrder("polaris", "polaris-admin-tool", "trino")
            .allSatisfy(name -> assertThat(DockerImages.all().get(name).unpinned()).isNotBlank());
    }

    @Test
    void bundledImagesAreAllCategorised() {
        assertThat(DockerImages.all().values()).isNotEmpty().allSatisfy(image -> assertThat(image.category()).isNotNull());
    }

    @Test
    void tagPlaceholdersAreResolved() {
        assertThat(parse("images:\n  p:\n    image: apache/polaris\n    tag: ${polaris.version}\n").get("p").reference())
            .isEqualTo("apache/polaris:1.2.3");
    }

    @Test
    void tagsAreStringsAsWrittenWhateverTheyLookLike() {
        assertThat(parse("images:\n  p: {image: a/b, tag: 3.10}\n  q: {image: a/b, tag: 481}\n  r: {image: a/b, tag: 1e3}\n"))
            .extractingByKeys("p", "q", "r").extracting(DockerImageDefinitions.Definition::tag).containsExactly("3.10", "481", "1e3");
    }

    @Test
    void pinnedImageReferenceHasTheDigest() {
        assertThat(parse("images:\n  p:\n    image: a/b\n    tag: '1'\n    digest: " + DIGEST + "\n").get("p").reference())
            .isEqualTo("a/b:1@" + DIGEST);
    }

    @Test
    void anImageHasEitherADigestOrTheReasonItHasNone() {
        assertThat(parse("images:\n  p: {image: a/b, tag: '1', unpinned: because}\n").get("p").unpinned()).isEqualTo("because");
        assertThatThrownBy(() -> parse("images:\n  p: {image: a/b, tag: '1', unpinned: because, digest: " + DIGEST + "}\n"))
            .hasMessageContaining("both a digest and the reason");
    }

    @Test
    void invalidImagesFailNamingTheFileAndTheProblem() {
        assertThatThrownBy(() -> parse("images:\n  p:\n    tag: '1'\n"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("test.yaml").hasMessageContaining("p has no 'image'");
        assertThatThrownBy(() -> parse("images:\n  p:\n    image: a/b\n"))
            .hasMessageContaining("p has no 'tag'");
        assertThatThrownBy(() -> parse("images:\n  p:\n    image: a/b\n    tag: '1'\n    digest: abc\n"))
            .hasMessageContaining("digest");
        assertThatThrownBy(() -> parse("images:\n  p:\n    image: a/b\n    tag: '1'\n    category: mine\n"))
            .hasMessageContaining("unknown category 'mine'");
        assertThatThrownBy(() -> parse("images:\n  p:\n    image: a/b\n    tag: ${nope}\n"))
            .hasMessageContaining("unknown placeholder");
        assertThatThrownBy(() -> parse("images:\n  p:\n    image: a/b\n    tag: '1'\n    colour: red\n"))
            .hasMessageContaining("unknown 'colour'");
        assertThatThrownBy(() -> parse("other: 1\n")).hasMessageContaining("unknown 'other'");
        assertThatThrownBy(() -> parse("images:\n  p: {image: a, tag: '1'}\n  p: {image: b, tag: '2'}\n"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void imagesFileIsTheNamedOneThenTheConfigDirOneElseBundled(@TempDir Path dir) throws IOException {
        assertThat(DockerImageDefinitions.imagesFile(null, dir.toString())).isNull();
        Path override = Files.writeString(dir.resolve(DockerImageDefinitions.IMAGES_FILE), "images: {}");
        assertThat(DockerImageDefinitions.imagesFile(null, dir.toString())).isEqualTo(override);
        assertThat(DockerImageDefinitions.imagesFile("bundled", dir.toString())).isNull();
        assertThatThrownBy(() -> DockerImageDefinitions.imagesFile(dir.resolve("missing.yaml").toString(), dir.toString()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("not found");
    }

    @Test
    void bundledImagesCanBeExportedToTheConfigDirWithoutOverwriting(@TempDir Path dir) throws IOException {
        Path configDir = dir.resolve("config");
        Path file = DockerImageDefinitions.exportBundled(configDir);
        assertThat(file).isEqualTo(configDir.resolve(DockerImageDefinitions.IMAGES_FILE));
        // what it exports is what is bundled
        assertThat(DockerImageDefinitions.load(file, key -> "1").keySet()).isEqualTo(DockerImageDefinitions.load(null, key -> "1").keySet());
        Files.writeString(file, "mine");
        assertThatThrownBy(() -> DockerImageDefinitions.exportBundled(configDir))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("exists already");
        assertThat(file).hasContent("mine");
    }
}
