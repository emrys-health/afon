package health.emrys.afon.cdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CleanupHelper {

    protected static void outputCleanupCommands(String cleanupCommands, String[] args) {
        if (args.length > 0) {
            Path path = Path.of(args[0]);
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
