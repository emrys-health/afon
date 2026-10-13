package health.emrys.afon.ops.steps;

import ch.vorburger.exec.ManagedProcessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code tofu init} is retried with {@code -upgrade} only if the stack dir locks provider versions that the plan no longer
 * pins, and not for any other failure.  A process that exits with an error makes {@code run} throw, with its output, stdout
 * and stderr, in the message of the exception.
 */
class StepActionsTofuInitTest {

    /** What tofu printed when the lock file of the stack dir pinned azurerm 4.34.0, but the plan 4.81.0, as wrapped by a terminal. */
    private static final String LOCK_MISMATCH = """
        \u001B[31m╷\u001B[0m
        \u001B[31m│\u001B[0m \u001B[1mError: \u001B[0mFailed to resolve provider packages
        │
        │ Could not resolve provider hashicorp/azurerm: locked provider
        │ registry.opentofu.org/hashicorp/azurerm 4.34.0 does not match configured
        │ version constraint 4.81.0; must use tofu init -upgrade to allow selection
        │ of new versions
        ╵
        """;

    private final List<String> commands = new ArrayList<>();
    /** What each run does: null to succeed, else the message of the failure it throws, as the output of the process. */
    private final List<String> failures = new ArrayList<>();

    private final StepActions actions = new StepActions() {
        @Override
        public int run(String command, Path path, OutputStream stdOut, String... args) throws ManagedProcessException {
            commands.add(command + " " + String.join(" ", args));
            String failure = failures.remove(0);
            if (failure != null) {
                throw new ManagedProcessException("Program [" + command + "] failed with Exception: , last 7000 lines of console:\n" + failure);
            }
            return 0;
        }
    };

    private int init() throws Exception {
        return actions.tofuInit(Path.of("."), OutputStream.nullOutputStream());
    }

    @Test
    void doesNotUpgradeWhenInitSucceeds() throws Exception {
        failures.add(null);

        assertThat(init()).isZero();
        assertThat(commands).containsExactly("tofu init");
    }

    @Test
    void upgradesWhenTheLockedProvidersDoNotMatchThePlan() throws Exception {
        failures.add(LOCK_MISMATCH);
        failures.add(null);

        assertThat(init()).isZero();
        assertThat(commands).containsExactly("tofu init", "tofu init -upgrade");
    }

    @Test
    void failsIfTheUpgradeFailsToo() {
        failures.add(LOCK_MISMATCH);
        failures.add("Error: Failed to query available provider packages: could not connect to registry.opentofu.org");

        assertThatThrownBy(this::init).isInstanceOf(ManagedProcessException.class).hasMessageContaining("could not connect");
        assertThat(commands).containsExactly("tofu init", "tofu init -upgrade");
    }

    @Test
    void doesNotRetryAnyOtherFailure() {
        failures.add("Error: Failed to query available provider packages: could not connect to registry.opentofu.org");

        assertThatThrownBy(this::init).isInstanceOf(ManagedProcessException.class).hasMessageContaining("could not connect");
        assertThat(commands).containsExactly("tofu init");
    }

    @Test
    void recognisesTheMismatchWhateverTheTerminalDidToItsMessage() {
        assertThat(StepActions.isProviderLockMismatch(LOCK_MISMATCH)).isTrue();
        assertThat(StepActions.isProviderLockMismatch("must use tofu\ninit -upgrade to allow")).isTrue();
        assertThat(StepActions.isProviderLockMismatch("Error: Failed to resolve provider packages")).isFalse();
        assertThat(StepActions.isProviderLockMismatch("")).isFalse();
    }

    @Test
    void aFailingProcessThrowsWithItsStderrInTheMessage(@TempDir Path dir) throws Exception {
        // a real process, with the real run, as what tofu init prints on failure is on stderr, which is where the mismatch is found
        Path script = Files.writeString(dir.resolve("fails.sh"), "echo on stdout\necho on stderr 1>&2\nexit 3\n");

        assertThatThrownBy(() -> new StepActions().run("sh", dir, OutputStream.nullOutputStream(), script.toString()))
            .isInstanceOf(ManagedProcessException.class).hasMessageContaining("on stderr").hasMessageContaining("on stdout");
    }
}
