package health.emrys.afon.ops.steps;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CleanupsTest {

    private final List<String> ran = new ArrayList<>();

    private Step step(String name) {
        return new Step(name, name, () -> ran.add(name));
    }

    @Test
    void runsTheCleanupOfAStepThatStartedWhenALaterOneFails() {
        Cleanups cleanups = new Cleanups();
        Step admit = step("admit").withCleanup(step("restrict"));
        cleanups.started(admit);
        cleanups.completed(admit);

        cleanups.runPending();

        assertThat(ran).containsExactly("restrict");
    }

    @Test
    void runsTheCleanupOfTheStepThatFailsToo() {
        // it may have done part of what it does
        Cleanups cleanups = new Cleanups();
        cleanups.started(step("admit").withCleanup(step("restrict")));

        cleanups.runPending();

        assertThat(ran).containsExactly("restrict");
    }

    @Test
    void doesNotRunTheCleanupOnceItHasRunAsAStepOfItsOwn() {
        Cleanups cleanups = new Cleanups();
        Step restrict = step("restrict");
        cleanups.started(step("admit").withCleanup(restrict));
        cleanups.started(restrict);
        cleanups.completed(restrict);

        cleanups.runPending();

        assertThat(ran).isEmpty();
    }

    @Test
    void runsNothingForStepsWithoutCleanup() {
        Cleanups cleanups = new Cleanups();
        cleanups.started(step("a"));
        cleanups.completed(step("a"));

        cleanups.runPending();

        assertThat(ran).isEmpty();
    }

    @Test
    void runsTheLatestCleanupFirstAndALaterOneEvenIfAnotherFails() {
        Cleanups cleanups = new Cleanups();
        cleanups.started(step("first").withCleanup(step("undo first")));
        cleanups.started(step("second").withCleanup(new Step("undo second", "undo second", () -> {
            ran.add("undo second");
            throw new IllegalStateException("boom");
        })));

        cleanups.runPending();

        assertThat(ran).containsExactly("undo second", "undo first");
        // and each runs once
        cleanups.runPending();
        assertThat(ran).hasSize(2);
    }

    @Test
    void aStepWithACleanupKeepsItsNameAndAction() throws Exception {
        Step restrict = step("restrict");
        Step admit = step("admit").withCleanup(restrict);

        admit.getFunction().run();

        assertThat(admit.getName()).isEqualTo("admit");
        assertThat(admit.getCleanup()).isSameAs(restrict);
        assertThat(ran).containsExactly("admit");
    }
}
