package health.emrys.afon.ops.steps;

import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code current.config} links to the kubeconfig of the stack deployed last, and goes with that stack.
 */
class StepActionsCurrentKubeConfigTest {

    @TempDir
    Path cdktf;

    private final StepActions actions = new StepActions();
    private       String      previous;

    @BeforeEach
    void setCdktf() throws IOException {
        previous = System.getProperty(ConfigUtils.AFON_CDKTF_PROPERTY);
        System.setProperty(ConfigUtils.AFON_CDKTF_PROPERTY, cdktf.toString());
        Files.createDirectories(cdktf.resolve("kube"));
    }

    @AfterEach
    void restoreCdktf() {
        if (previous == null) {
            System.clearProperty(ConfigUtils.AFON_CDKTF_PROPERTY);
        } else {
            System.setProperty(ConfigUtils.AFON_CDKTF_PROPERTY, previous);
        }
    }

    private Path link(String stack) throws IOException {
        Path current = Path.of(ConfigUtils.currentKubeConfigPath());
        Files.deleteIfExists(current);
        return Files.createSymbolicLink(current, Path.of(ConfigUtils.kubeConfigPath(StackId.parse(stack))));
    }

    @Test
    void removesTheLinkWhenItNamesTheDeletedStack() throws IOException {
        Path current = link("gcp-full");

        actions.removeCurrentKubeConfigIfNamed(StackId.parse("gcp-full"));

        assertThat(Files.isSymbolicLink(current)).isFalse();
    }

    @Test
    void leavesTheLinkOfAnotherStack() throws IOException {
        Path current = link("azure-full");

        actions.removeCurrentKubeConfigIfNamed(StackId.parse("gcp-full"));

        assertThat(Files.isSymbolicLink(current)).isTrue();
    }

    @Test
    void doesNothingWithoutALink() {
        actions.removeCurrentKubeConfigIfNamed(StackId.parse("gcp-full"));

        assertThat(Path.of(ConfigUtils.currentKubeConfigPath())).doesNotExist();
    }
}
