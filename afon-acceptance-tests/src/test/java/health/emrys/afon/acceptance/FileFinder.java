package health.emrys.afon.acceptance;

import java.io.File;
import java.io.IOException;

public class FileFinder {

    public static void main(String[] args) throws IOException {
        // smoke test:
        System.out.println(
            new FileFinder()
                .fullPathOf("scripts/trino/catalog/")
                .getAbsolutePath());
    }

    public FileFinder() {}

    public File fullPathOf(String filename) throws IOException {
        var file = new File(filename);
        if (!file.exists()) {
            file = new File("../" + filename);
            if (!file.exists()) {
                throw new IOException("Could not find file " + filename);
            }
        }
        return file;
    }

}
