package health.emrys.afon.cdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AbstractCleanupHelper {

    protected static void outputCleanupCommands(String[] args, String cleanupCommands) {
        if (args.length > 0) {
            Path path = Path.of(args[0]);
            System.out.println("\u001B[31mWriting cleanup commands to " + path + "\u001B[0m");
            AbstractCleanupHelper.writeToFile(path, cleanupCommands);
        } else {
            System.out.println("\u001B[31mCleanup commands:\u001B[0m");
            System.out.println("\u001B[31m" + cleanupCommands + "\u001B[0m");
        }
    }

    private static void writeToFile(Path path, String cleanupCommands) {
        try {
            Files.write(path, cleanupCommands.getBytes());
        } catch (IOException e) {
            System.out.println("Failed to write to file " + path);
            throw new RuntimeException(e);
        }
    }
}
