package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projects of the tests are templates, which every test renders for the cloud it is run on: {@code ${cloud}} is the cloud in lower case,
 * as in {@code cloud: azure} or {@code azure-full}, and {@code ${CLOUD}} in upper case, as in {@code AZURE_JUPYTER_DOMAIN_NAME}.  No cloud is
 * special to the code, so no test has one of its own: each is run on all of them.
 */
final class ProjectFixtures {

    /** The example project, a template. */
    static final Path EXAMPLE = Path.of("src/test/resources/project.yaml");

    /** The example of the data project, a template. */
    static final Path DATA_EXAMPLE = Path.of("src/test/resources/data-project.yaml");

    /** The example of the identity project, a template. */
    static final Path IDENTITY_EXAMPLE = Path.of("src/test/resources/identity-project.yaml");

    private ProjectFixtures() {
    }

    /**
     * @return the TRE project that the text defines
     */
    static TreProject tre(String yaml) {
        AfonProject project = FileProjectProvider.parse(yaml);
        assertThat(project).isInstanceOf(TreProject.class);
        return (TreProject) project;
    }

    /**
     * @return the identity project that the text defines
     */
    static IdentityProject identity(String yaml) {
        AfonProject project = FileProjectProvider.parse(yaml);
        assertThat(project).isInstanceOf(IdentityProject.class);
        return (IdentityProject) project;
    }

    /**
     * @return the data project that the text defines
     */
    static DataProject data(String yaml) {
        AfonProject project = FileProjectProvider.parse(yaml);
        assertThat(project).isInstanceOf(DataProject.class);
        return (DataProject) project;
    }

    /**
     * @return the example data project, for the cloud
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
     * @return the example identity project, for the cloud
     */
    static String identityExample(AfonCloud cloud) {
        try {
            return render(Files.readString(IDENTITY_EXAMPLE), cloud);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the file that the example project, for the cloud, is written to
     */
    static Path writeExample(Path file, AfonCloud cloud) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, example(cloud));
    }
}
