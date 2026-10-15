package health.emrys.afon.ops.synth;

import health.emrys.afon.config.StackId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Checks that a stack synthesises to the very same plan in each JVM, and not only in one.
 * <p>
 * What goes into the plan must not depend on anything that differs from one run to the next, eg the iteration order of an
 * immutable {@link java.util.Map#of}, or the time that a jar was made: tofu sees a different string as a change, and
 * replaces what holds it, eg a {@code kubectl} manifest, on every deploy.  Within a JVM the order of those maps is the same,
 * so the plan is synthesised in a JVM of its own for each run, as two runs agree by chance if there are few of them.
 * <p>
 * The runs all synthesise into the same stacks dir, as the path of the tofu state is part of the plan, one after the other:
 * the plan of each is copied out before the next overwrites it.
 */
public final class DeterministicSynthesis {

    /**
     * Synthesises the plan of a stack into the stacks dir, in a JVM of its own.
     */
    @FunctionalInterface
    public interface Synthesiser {
        void synthesise(StackId stack) throws IOException, InterruptedException;
    }

    private DeterministicSynthesis() {
    }

    /**
     * Synthesises the stack the given number of times, each in a JVM of its own, and compares the plans.
     *
     * @param stacksDir the dir the plans are synthesised into
     * @param runs      the number of times to synthesise, at least two
     * @return the differences between the first plan and each of the others, as the lines of a report that has no values in it,
     * as the plans hold the config, or none if they are the same
     * @throws IOException if a synthesis fails
     */
    public static List<String> check(StackId stack, Path stacksDir, int runs, Synthesiser synthesiser)
            throws IOException, InterruptedException {
        if (runs < 2) {
            throw new IllegalArgumentException("At least two runs are needed to compare, not " + runs);
        }
        Path snapshots = Files.createTempDirectory("afon-determinism");
        try {
            for (int run = 1; run <= runs; run++) {
                synthesiser.synthesise(stack);
                // the plan is in the stack dir named as the stack
                PlanSnapshots.snapshot(stacksDir, snapshots.resolve("run" + run), List.of(stack.toString()));
            }
            List<String> differences = new java.util.ArrayList<>();
            for (int run = 2; run <= runs; run++) {
                PlanSnapshots.Comparison comparison = PlanSnapshots.compare(snapshots.resolve("run1"), snapshots.resolve("run" + run), false);
                if (comparison.differs()) {
                    differences.add("Run 1 and run " + run + " differ:");
                    differences.addAll(comparison.lines());
                }
            }
            return differences;
        } finally {
            delete(snapshots);
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
