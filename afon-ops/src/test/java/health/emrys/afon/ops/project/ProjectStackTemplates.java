package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The example project stack of {@code afon-project}'s tests is a template, which every test renders for the cloud it is run on: {@code ${cloud}} is
 * the cloud in lower case, as in {@code azure-full}, and {@code ${CLOUD}} in upper case.  No cloud is special to the commands, so every test is
 * run on every cloud.
 */
final class ProjectStackTemplates {

    private static final Path EXAMPLE = Path.of("../afon-project/src/test/resources/project.yaml");
    private static final Path IDENTITY_EXAMPLE = Path.of("../afon-project/src/test/resources/identity-project.yaml");
    private static final Path DATA_EXAMPLE = Path.of("../afon-project/src/test/resources/data-project.yaml");

    private ProjectStackTemplates() {
    }

    static String lower(AfonCloud cloud) {
        return cloud.toString().toLowerCase(Locale.ROOT);
    }

    static String render(String template, AfonCloud cloud) {
        return template.replace("${cloud}", lower(cloud)).replace("${CLOUD}", cloud.name());
    }

    /**
     * @return the example project stack, for the cloud
     */
    static String example(AfonCloud cloud) {
        try {
            return render(Files.readString(EXAMPLE), cloud);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the example identity project stack, for the cloud
     */
    static String identityExample(AfonCloud cloud) {
        try {
            return render(Files.readString(IDENTITY_EXAMPLE), cloud);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the example identity project stack, for the cloud, written as the project stack of the dir {@code <home>/projects/<project-stack>}
     */
    static Path writeIdentity(Path home, String projectStack, AfonCloud cloud) throws IOException {
        Path file = home.resolve("projects").resolve(projectStack).resolve("project.yaml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, identityExample(cloud));
    }

    /**
     * @return the example project stack, for the cloud, written as the project stack of the dir {@code <home>/projects/<project-stack>}
     */
    static Path write(Path home, String projectStack, AfonCloud cloud) throws IOException {
        Path file = home.resolve("projects").resolve(projectStack).resolve("project.yaml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, example(cloud));
    }

    /**
     * @return the example data project stack, for the cloud
     */
    static String dataExample(AfonCloud cloud) {
        try {
            return render(Files.readString(DATA_EXAMPLE), cloud);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the example data project stack, for the cloud, written as the project stack of the dir {@code <home>/projects/<project-stack>}
     */
    static Path writeData(Path home, String projectStack, AfonCloud cloud) throws IOException {
        Path file = home.resolve("projects").resolve(projectStack).resolve("project.yaml");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, dataExample(cloud));
    }
}
