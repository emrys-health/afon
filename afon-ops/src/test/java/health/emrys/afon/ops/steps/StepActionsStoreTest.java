package health.emrys.afon.ops.steps;

import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state of a stack is stored in the object storage of its cloud by the command cdktf-store, which a failure to run only warns about.
 */
class StepActionsStoreTest {

    /** The args of the runs of the script cdktf-store. */
    private final List<String> runs = new ArrayList<>();

    private boolean hasState = true;

    private RuntimeException failure;

    private final StepActions actions = new StepActions() {
        @Override
        protected boolean exists(Path path) {
            return hasState;
        }

        @Override
        protected void runStoreScript(String... args) {
            runs.add(String.join(" ", args));
            if (failure != null) {
                throw failure;
            }
        }
    };

    @Test
    void pushesTheStateOfTheStackToTheStoreOfItsCloudUnderTheCurrentTag() {
        actions.pushStateToStore(StackId.parse("azure-polaris"));

        assertThat(runs).containsExactly("push current azure-polaris --force --cloud azure");
    }

    @Test
    void doesNotPushAStackWithoutState() {
        hasState = false;

        actions.pushStateToStore(StackId.parse("aws-full"));

        assertThat(runs).isEmpty();
    }

    @Test
    void deletesTheStateOfTheStackFromTheStore() {
        actions.deleteStateFromStore(StackId.parse("gcp-full"));

        assertThat(runs).containsExactly("delete current gcp-full --cloud gcp");
    }

    @Test
    void aFailureToStoreIsOnlyAWarning() {
        failure = new RuntimeException("Failed to run script cdktf/store.sh");

        actions.pushStateToStore(StackId.parse("aws-full"));
        actions.deleteStateFromStore(StackId.parse("aws-full"));

        assertThat(runs).hasSize(2);
    }
}
