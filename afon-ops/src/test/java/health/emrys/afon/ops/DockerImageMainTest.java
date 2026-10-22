package health.emrys.afon.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerImageMainTest {

    @Test
    void printsTheReferenceOfAnImageOfTheImagesFile() {
        assertThat(DockerImageMain.lines(new String[]{"postgres-alpine"})).containsExactly("AFON_DOCKER_IMAGE=postgres:16-alpine");
    }

    @Test
    void rejectsWrongArgsAndUnknownImages() {
        assertThatThrownBy(() -> DockerImageMain.lines(new String[]{}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Usage: docker-image <name|--export>");
        assertThatThrownBy(() -> DockerImageMain.lines(new String[]{"nope"}))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown image 'nope'");
    }
}
