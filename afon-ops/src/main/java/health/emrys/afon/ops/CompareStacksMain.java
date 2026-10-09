package health.emrys.afon.ops;

import health.emrys.afon.ops.synth.PlanSnapshots;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Compares the plans of the stacks in two dirs, each a snapshot, see {@link SnapshotStacksMain}, or the stacks dir:
 * <pre>
 * compare-stacks &lt;before-dir&gt; &lt;after-dir&gt;
 * </pre>
 * It lists the resources that were added and removed, which tofu creates and destroys (a renamed one is both), then the values that
 * changed, with the long ones reduced to their hash.  It exits with 1 if the plans differ.
 */
public class CompareStacksMain {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Usage: compare-stacks <before-dir> <after-dir>");
            System.exit(1);
            return;
        }
        PlanSnapshots.Comparison comparison;
        try {
            comparison = PlanSnapshots.compare(Path.of(args[0]), Path.of(args[1]));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        comparison.lines().forEach(System.out::println);
        if (comparison.differs()) {
            System.exit(1);
        }
    }
}
