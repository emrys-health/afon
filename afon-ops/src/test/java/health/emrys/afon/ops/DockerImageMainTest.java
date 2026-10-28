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

    @Test
    void signTakesTheCloudGivenElseTheDefaultAndNames() {
        var given = DockerImageMain.SignArguments.of(new String[]{"--sign", "--cloud", "azure", "keycloak"}, "");
        assertThat(given.cloud()).isEqualTo(health.emrys.afon.config.AfonCloud.AZURE);
        assertThat(given.names()).containsExactly("keycloak");
        assertThat(DockerImageMain.SignArguments.of(new String[]{"--sign"}, "gcp").cloud()).isEqualTo(health.emrys.afon.config.AfonCloud.GCP);
        assertThatThrownBy(() -> DockerImageMain.SignArguments.of(new String[]{"--sign"}, "ALL"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one cloud");
        assertThatThrownBy(() -> DockerImageMain.SignArguments.of(new String[]{"--sign", "--cloud", "mars"}, ""))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown cloud 'mars'");
        assertThatThrownBy(() -> DockerImageMain.SignArguments.of(new String[]{"--sign", "--bogus"}, "aws"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown option --bogus");
    }

    @Test
    void publicKeyTakesTheCloudAndNoImageNames() {
        assertThat(DockerImageMain.SignArguments.of(new String[]{"--public-key", "--cloud", "gcp"}, "", false).cloud())
            .isEqualTo(health.emrys.afon.config.AfonCloud.GCP);
        assertThatThrownBy(() -> DockerImageMain.SignArguments.of(new String[]{"--public-key", "keycloak"}, "aws", false))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("takes no image names");
        assertThatThrownBy(() -> DockerImageMain.SignArguments.of(new String[]{"--public-key", "--bogus"}, "aws", false))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("usage: docker-image --public-key [--cloud <cloud>]");
    }

    @Test
    void signTakesTheFileToRecordItsResultIn() {
        var arguments = DockerImageMain.SignArguments.of(new String[]{"--sign", "--report", "out/sign.md", "keycloak"}, "aws");
        assertThat(arguments.report()).isEqualTo(java.nio.file.Path.of("out/sign.md"));
        assertThat(arguments.names()).containsExactly("keycloak");
        assertThat(DockerImageMain.SignArguments.of(new String[]{"--sign"}, "aws").report()).isNull();
        assertThatThrownBy(() -> DockerImageMain.SignArguments.of(new String[]{"--sign", "--report", "out.txt"}, "aws"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("*.json or *.md");
        assertThatThrownBy(() -> DockerImageMain.SignArguments.of(new String[]{"--sign", "--report"}, "aws"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires a file");
        assertThatThrownBy(() -> DockerImageMain.SignArguments.of(new String[]{"--public-key", "--report", "x.md"}, "aws", false))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown option --report");
    }
}
