package health.emrys.afon.demo;

import org.apache.commons.io.file.PathUtils;
import org.slf4j.Logger;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

public class DeployFullStackAws {

    public static String capitalizeFirst(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }


    private static final Logger log = getLogger(DeployFullStackAws.class);

    public static void main(String[] args) throws IOException {
        new DeployFullStackAws().writeDocumentation();
    }

    private void writeDocumentation() {
        String fileName = "test.md";
        try {
            Path path = Path.of(fileName);
            if (path.toFile().exists()) {
                PathUtils.delete(path);
            }
            writeTo(fileName);
        } catch (IOException e) {
            log.error("Could not write to " +  fileName, e);
        }
    }

    private void writeTo(String fileName) throws IOException {
        try (OutputStream os = new FileOutputStream(fileName)) {
            writeSteps(os);
        }
    }

    private void writeSteps(OutputStream fos) {
        List<Step> steps = DeployAwsFulltStackMain.steps(new Steps(new StepDocumentation(fos)));
        StepRunner app = new StepRunner();
        app.runSteps(steps.stream().map(x -> decorating(x, fos)).toList());
    }

    private Step decorating(Step step, OutputStream fos) {
        final PrintWriter writer = new PrintWriter(fos);
        return new Step(step.name, step.description, () -> {
            writer.println(format("# %s", capitalizeFirst(step.description)));
            writer.flush();
            step.function.run();
        });
    }

}
