package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The example project of {@code afon-project}'s tests is a template, which every test renders for the cloud it is run on: {@code ${cloud}} is
 * the cloud in lower case, as in {@code azure-full}, and {@code ${CLOUD}} in upper case.  No cloud is special to the commands, so every test is
 * run on every cloud.
 */
final class ProjectTemplates {

    private static final Path EXAMPLE = Path.of("../afon-project/src/test/resources/project.yaml");

    private ProjectTemplates() {
    }

    static String lower(AfonCloud cloud) {
        return cloud.toString().toLowerCase(Locale.ROOT);
    }

    static String render(String template, AfonCloud cloud) {
        return template.replace("${cloud}", lower(cloud)).replace("${CLOUD}", cloud.name());
    }

    /**
     * @return the example project, for the cloud
     */
    static String example(AfonCloud cloud) {
        try {
            return render(Files.readString(EXAMPLE), cloud);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the example project, for the cloud, written as the project of the dir {@code <home>/projects/<project>}
     */
    static Path write(Path home, String project, AfonCloud cloud) throws IOException {
        Path file = home.resolve("projects").resolve(project).resolve("project.yaml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, example(cloud));
    }
}
