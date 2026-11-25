package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project stacks of the tests are templates, which every test renders for the cloud it is run on: {@code ${cloud}} is the cloud in lower case,
 * as in {@code cloud: azure} or {@code azure-full}, and {@code ${CLOUD}} in upper case, as in {@code AZURE_JUPYTER_DOMAIN_NAME}.  No cloud is
 * special to the code, so no test has one of its own: each is run on all of them.
 */
final class ProjectStackFixtures {

    /** The example project stack, a template. */
    static final Path EXAMPLE = Path.of("src/test/resources/project.yaml");

    /** The example of the data project stack, a template. */
    static final Path DATA_EXAMPLE = Path.of("src/test/resources/data-project.yaml");

    /** The example of the identity project stack, a template. */
    static final Path IDENTITY_EXAMPLE = Path.of("src/test/resources/identity-project.yaml");

    private ProjectStackFixtures() {
    }

    /**
     * @return the TRE project stack that the text defines
     */
    static TreProjectStack tre(String yaml) {
        ProjectStack projectStack = FileProjectStackProvider.parse(yaml);
        assertThat(projectStack).isInstanceOf(TreProjectStack.class);
        return (TreProjectStack) projectStack;
    }

    /**
     * @return the identity project stack that the text defines
     */
    static IdentityProjectStack identity(String yaml) {
        ProjectStack projectStack = FileProjectStackProvider.parse(yaml);
        assertThat(projectStack).isInstanceOf(IdentityProjectStack.class);
        return (IdentityProjectStack) projectStack;
    }

    /**
     * @return the data project stack that the text defines
     */
    static DataProjectStack data(String yaml) {
        ProjectStack projectStack = FileProjectStackProvider.parse(yaml);
        assertThat(projectStack).isInstanceOf(DataProjectStack.class);
        return (DataProjectStack) projectStack;
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
     * @return the cloud in lower case, as it is in {@code cloud: azure} and the ids of its stacks
     */
    static String lower(AfonCloud cloud) {
        return cloud.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * @return the template, for the cloud
     */
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
     * @return the file that the example project stack, for the cloud, is written to
     */
    static Path writeExample(Path file, AfonCloud cloud) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, example(cloud));
    }
    /**
     * Writes the secret of the TRE client of the project stacks that the tests use, {@code <home>/projects/<id>/secrets/tre.secret}, which {@code project-secret} makes
     * and {@code configure} puts in the config of a TRE.
     */
    static void writeSecrets(java.nio.file.Path home) {
        try {
            for (String id : java.util.List.of("xyz-yyyy-nnnn", "abc-def", "xyz-yyyy-nnn2")) {
                java.nio.file.Path keys = home.resolve("projects").resolve(id).resolve("secrets");
                java.nio.file.Files.createDirectories(keys);
                java.nio.file.Files.writeString(keys.resolve("tre.secret"), "secret-of-" + id + "\n");
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
