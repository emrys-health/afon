package health.emrys.afon.ops.steps;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StepTest {
    @Test
    public void testAndThen() throws Exception {
        List   results   = new ArrayList();
        String test1Name = "test1";
        String test2Name = "test2";
        Step   step1     = new Step(test1Name, test1Name, () -> results.add(test1Name));
        Step   step2     = new Step(test2Name, test2Name, () -> results.add(test2Name));
        step1.andThen(step2).getFunction().run();
        assertEquals(results, List.of(test1Name, test2Name));
    }

    @Test
    public void testSequence() throws Exception {
        List<String> results = new ArrayList<>();
        Step step1 = new Step("test1", "first", () -> results.add("test1"));
        Step step2 = new Step("test2", "second", () -> results.add("test2"));
        Step step3 = new Step("test3", "third", () -> results.add("test3"));
        Step sequence = Step.sequence("all_tests", "run all tests", step1, step2, step3);
        assertEquals("all_tests", sequence.getName());
        assertEquals("run all tests", sequence.getDescription());
        sequence.getFunction().run();
        assertEquals(List.of("test1", "test2", "test3"), results);
    }

    @Test
    public void testSequenceStopsOnFailure() {
        List<String> results = new ArrayList<>();
        Step step1 = new Step("test1", "first", () -> results.add("test1"));
        Step failing = new Step("failing", "failing", () -> {
            throw new IllegalStateException("failed");
        });
        Step step3 = new Step("test3", "third", () -> results.add("test3"));
        Step sequence = Step.sequence("all_tests", "run all tests", step1, failing, step3);
        assertThrows(IllegalStateException.class, () -> sequence.getFunction().run());
        assertEquals(List.of("test1"), results);
    }

    @Test
    public void testEmptySequenceDoesNothing() throws Exception {
        Step sequence = Step.sequence("empty", "nothing to run");
        sequence.getFunction().run();
        assertEquals("empty", sequence.getName());
    }
}
