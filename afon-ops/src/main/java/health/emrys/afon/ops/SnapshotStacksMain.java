package health.emrys.afon.ops;

import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.ops.synth.PlanSnapshots;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Snapshots the plans that were synthesised into the stacks dir, to compare them, with {@link CompareStacksMain}, with those of another
 * time, eg before and after a refactoring:
 * <pre>
 * snapshot-stacks &lt;dir&gt; [&lt;stack-id&gt;...]
 * </pre>
 * It copies to {@code <dir>/<stack-id>/cdk.tf.json} the plan of each stack, or of those given, that was synthesised, eg by
 * {@code validate-stack}, in {@link ConfigUtils#stacksDir()}.  It copies nothing but those files.
 */
public class SnapshotStacksMain {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: snapshot-stacks <dir> [<stack-id>...]");
            System.exit(1);
            return;
        }
        Path stacksDir = Path.of(ConfigUtils.stacksDir());
        List<String> stacks;
        try {
            stacks = PlanSnapshots.snapshot(stacksDir, Path.of(args[0]), Arrays.asList(args).subList(1, args.length));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + ", synthesise it first, eg with validate-stack");
            System.exit(1);
            return;
        }
        if (stacks.isEmpty()) {
            System.err.println("No stack synthesised in " + stacksDir + ", synthesise it first, eg with validate-stack");
            System.exit(1);
            return;
        }
        System.out.printf("Snapshot of %d stacks from %s written to %s: %s%n", stacks.size(), stacksDir, args[0], String.join(", ", stacks));
    }
}
