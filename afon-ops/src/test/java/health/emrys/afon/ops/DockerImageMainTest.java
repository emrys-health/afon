package health.emrys.afon.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerImageMainTest {

    @Test
    void printsTheReferenceOfAnImageOfTheImagesFile() {
        assertThat(DockerImageMain.lines(new String[]{"postgres-alpine"})).singleElement().asString().startsWith("AFON_DOCKER_IMAGE=postgres:16-alpine@sha256:");
    }

    @Test
    void rejectsWrongArgsAndUnknownImages() {
        assertThatThrownBy(() -> DockerImageMain.lines(new String[]{}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Usage: docker-image <name|--export|--pin");
        assertThatThrownBy(() -> DockerImageMain.lines(new String[]{"nope"}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown image 'nope'");
    }

    @Test
    void pinPinsTheFileGivenElseThatOfTheConfigDir(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws java.io.IOException {
        var given = DockerImageMain.PinArguments.of(new String[]{"--pin", "--file", "x.yaml", "keycloak", "curl"}, dir.toString());
        assertThat(given.file()).isEqualTo(java.nio.file.Path.of("x.yaml"));
        assertThat(given.names()).containsExactly("keycloak", "curl");
        assertThatThrownBy(() -> DockerImageMain.PinArguments.of(new String[]{"--pin"}, dir.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--export");
        var file = java.nio.file.Files.writeString(dir.resolve("docker-images.yaml"), "images: {}");
        assertThat(DockerImageMain.PinArguments.of(new String[]{"--pin"}, dir.toString()).file()).isEqualTo(file);
        assertThatThrownBy(() -> DockerImageMain.PinArguments.of(new String[]{"--pin", "--bogus"}, dir.toString()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown option --bogus");
    }

    @Test
    void verifyTakesTheReportFileAndPinDoesNot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var arguments = DockerImageMain.PinArguments.of(new String[]{"--verify", "--report", "out/verify.md", "keycloak"}, dir.toString(), false);
        assertThat(arguments.report()).isEqualTo(java.nio.file.Path.of("out/verify.md"));
        assertThat(arguments.file()).isNull();
        assertThatThrownBy(() -> DockerImageMain.PinArguments.of(new String[]{"--verify", "--report", "out.txt"}, dir.toString(), false))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("*.json or *.md");
        assertThatThrownBy(() -> DockerImageMain.PinArguments.of(new String[]{"--verify", "--report"}, dir.toString(), false))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires a file");
    }
}
