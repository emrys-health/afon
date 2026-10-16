package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.StackId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base class of the helpers generating the commands to clean up a stack, whose mains take the args
 * {@code <stack-id> [<output-file>]}.
 */
public class CleanupHelper {

    /**
     * @return the stack id given as first arg, checked to be in the cloud
     * @throws IllegalArgumentException if there is no stack id, or it isn't in the cloud
     */
    protected static StackId stackIdOf(String[] args, AfonCloud cloud) {
        if (args.length == 0) {
            throw new IllegalArgumentException(String.format(
                "Cleanup requires args: <stack-id> [<output-file>], eg %s-<name>", cloud));
        }
        StackId stackId = StackDefinitions.resolve(StackId.parse(args[0]));
        if (stackId.cloud() != cloud) {
            throw new IllegalArgumentException(String.format("Stack %s is not in cloud %s", stackId, cloud));
        }
        return stackId;
    }

    /**
     * Outputs the cleanup commands to the output file, if given as second arg, else to the console.
     */
    protected static void outputCleanupCommands(String cleanupCommands, String[] args) {
        if (args.length > 1) {
            Path path = Path.of(args[1]);
            System.out.println("\u001B[31mWriting cleanup script to " + path + "\u001B[0m");
            CleanupHelper.writeToFile(cleanupCommands, path);
        } else {
            System.out.println("\u001B[31mCleanup commands:\u001B[0m");
            System.out.println("\u001B[31m" + cleanupCommands + "\u001B[0m");
        }
    }

    private static void writeToFile(String cleanupCommands, Path path) {
        try {
            Files.write(path, cleanupCommands.getBytes());
        } catch (IOException e) {
            System.out.println("Failed writing commands to file " + path);
            throw new RuntimeException(e);
        }
    }
}
