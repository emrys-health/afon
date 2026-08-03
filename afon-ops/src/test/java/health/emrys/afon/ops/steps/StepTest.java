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
}