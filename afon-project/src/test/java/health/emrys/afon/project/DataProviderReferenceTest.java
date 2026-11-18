package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.project.ProjectFixtures.lower;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A TRE project names the catalog of a Data Provider's project, {@code {project: lon, catalogs: [cvd]}} in {@code dataProviders.named}, and takes its endpoint, audience and storage from
 * that project, as its file and the config it is made from say them.  Every test is run on every cloud.
 */
class DataProviderReferenceTest {

    private static final String ACCOUNTS = "AZURE_SUBSCRIPTION=sub-1\nAWS_ACCOUNT_ID=111111111111\nGCP_PROJECT_ID=gcp-proj\n";

    @TempDir
    Path home;

    /** The Afon home with the env that the Data Provider is made from, and the Data Provider's project, on the cloud. */
    private void provider(AfonCloud cloud) throws IOException {
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS);
        Files.writeString(env.resolve("afon-" + lower(cloud) + ".properties"), cloud.name() + "_POLARIS_DOMAIN_NAME=" + lower(cloud) + "-polaris.dev.click\n");
        Files.writeString(env.resolve("catalogs.properties"), "");
        Path file = home.resolve("projects/lon/project.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ProjectFixtures.dataExample(cloud).replace("role: data\n", "role: data\n  env: dev\n"));
    }

    /** The TRE project's file, whose {@code dataProviders} are the lines given, under it. */
    private TreProject tre(AfonCloud cloud, String providers) throws IOException {
        String yaml = ProjectFixtures.example(cloud).replaceAll("(?s)dataProviders:.*?\\nmembers:", "dataProviders:\n" + providers + "\nmembers:");
        Path file = home.resolve("projects/xyz-yyyy-nnnn/project.yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
        return (TreProject) FileProjectProvider.forProject(home, "xyz-yyyy-nnnn").getProject();
    }

    private ProjectException invalid(AfonCloud cloud, String providers) throws IOException {
        assertThatThrownBy(() -> tre(cloud, providers)).isInstanceOf(ProjectException.class);
        try {
            tre(cloud, providers);
        } catch (ProjectException e) {
            return e;
        }
        throw new AssertionError();
    }

    /** The entries of {@code named}, as the lines under {@code dataProviders}. */
    private static String named(String... entries) {
        return "  named:\n" + String.join("\n", java.util.Arrays.stream(entries).map(e -> "    - " + e).toList());
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheEndpointTheAudienceAndTheStorageOfTheCatalogFromTheDataProvidersProject(AfonCloud cloud) throws IOException {
        provider(cloud);
        TreProject project = tre(cloud, named("{project: lon, catalogs: [cvd]}"));
        assertThat(project.dataProviders()).hasSize(1);
        TreProject.DataProvider provider = project.dataProviders().get(0);
        assertThat(provider.id()).isEqualTo("lon-cvd");
        assertThat(provider.project()).isEqualTo("lon");
        assertThat(provider.catalogUri()).isEqualTo("https://" + lower(cloud) + "-polaris.lon.dev.click/api/catalog");
        assertThat(provider.catalogName()).isEqualTo("cvd");
        assertThat(provider.audience()).isEqualTo("provider-lon");
        assertThat(provider.cloud()).isEqualTo(cloud);
        String account = switch (cloud) {
            case AZURE -> "sub-1";
            case AWS -> "111111111111";
            case GCP -> "gcp-proj";
        };
        String token = ProjectNames.token(cloud, account, "lon");
        assertThat(provider.bucket()).isEqualTo(cloud == AfonCloud.AZURE ? "afonstorage" + token : "afon-" + token + "-cvd");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheCloudOfTheDataProviderNotTheProjects(AfonCloud cloud) throws IOException {
        AfonCloud other = AfonCloud.values()[(cloud.ordinal() + 1) % AfonCloud.values().length];
        provider(other);
        TreProject project = tre(cloud, named("{project: lon, catalogs: [dbt]}"));
        assertThat(project.cloud()).isEqualTo(cloud);
        assertThat(project.dataProviders().get(0).cloud()).isEqualTo(other);
        assertThat(project.dataProviders().get(0).catalogName()).isEqualTo("dbt");
    }

    /**
     * End to end, in the config that a TRE is deployed with: the catalogs that its file names of a Data Provider's project, as that project and its env say
     * them, are in its catalogs file as catalogs from the Data Provider, read as any catalog is, and are not among those the stack makes.
     */
    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresATreWithTheCatalogsOfTheDataProvidersItNames(AfonCloud cloud) throws IOException {
        provider(cloud);
        Files.writeString(home.resolve("config-dev/secret.properties"), "");
        TreProject project = tre(cloud, named("{project: lon, catalogs: [cvd, dbt]}"));
        Path config = ProjectEnv.configure(project, home.resolve("config-dev"), home, home.resolve("projects/xyz-yyyy-nnnn/project.yaml"));

        var catalogs = new health.emrys.afon.config.AfonCatalogs(new health.emrys.afon.config.FilePropertyProvider(config.toString()));
        var fromDataProviders = catalogs.bySource(health.emrys.afon.config.AfonCatalogs.Source.DATA_PROVIDER);
        assertThat(fromDataProviders).extracting(health.emrys.afon.config.AfonCatalog::id).containsExactly("lon_cvd", "lon_dbt");
        assertThat(fromDataProviders).extracting(health.emrys.afon.config.AfonCatalog::name).containsExactly("cvd", "dbt");
        assertThat(fromDataProviders).extracting(health.emrys.afon.config.AfonCatalog::catalogUri)
            .containsOnly("https://" + lower(cloud) + "-polaris.lon.dev.click/api/catalog");
        assertThat(fromDataProviders).extracting(health.emrys.afon.config.AfonCatalog::audience).containsOnly("provider-lon");
        assertThat(fromDataProviders).extracting(health.emrys.afon.config.AfonCatalog::cloud).containsOnly(cloud);
        assertThat(catalogs.all()).extracting(health.emrys.afon.config.AfonCatalog::id).doesNotContain("lon_cvd", "lon_dbt");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void mixesCatalogsNamedAndDefinedInFull(AfonCloud cloud) throws IOException {
        provider(cloud);
        TreProject project = tre(cloud, named("{project: lon, catalogs: [cvd, dbt]}")
            + "\n  defined:\n    - {id: outside, catalogUri: \"https://polaris.example.org/api/catalog\", catalogName: w, audience: provider-outside, bucket: b}");
        assertThat(project.dataProviders()).extracting(TreProject.DataProvider::id).containsExactly("lon-cvd", "lon-dbt", "outside");
        assertThat(project.dataProviders()).extracting(TreProject.DataProvider::project).containsExactly("lon", "lon", null);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesSeveralCatalogsOfOneDataProviderNamedTogether(AfonCloud cloud) throws IOException {
        provider(cloud);
        TreProject project = tre(cloud, named("{project: lon, catalogs: [cvd, dbt]}"));
        assertThat(project.dataProviders()).extracting(TreProject.DataProvider::id).containsExactly("lon-cvd", "lon-dbt");
        assertThat(project.dataProviders()).extracting(TreProject.DataProvider::catalogName).containsExactly("cvd", "dbt");
        // one Polaris, one audience, and storage that is the Data Provider's: the endpoint and the audience are the same, the bucket is the catalog's
        assertThat(project.dataProviders()).extracting(TreProject.DataProvider::catalogUri).containsOnly("https://" + lower(cloud) + "-polaris.lon.dev.click/api/catalog");
        assertThat(project.dataProviders()).extracting(TreProject.DataProvider::audience).containsOnly("provider-lon");
        assertThat(project.dataProviders()).extracting(TreProject.DataProvider::project).containsOnly("lon");
        if (cloud != AfonCloud.AZURE) {
            assertThat(project.dataProviders().get(0).bucket()).isNotEqualTo(project.dataProviders().get(1).bucket());
        }
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnIdThatANamedCatalogAndADefinedEntryShare(AfonCloud cloud) throws IOException {
        provider(cloud);
        assertThat(invalid(cloud, named("{project: lon, catalogs: [cvd]}")
            + "\n  defined:\n    - {id: lon-cvd, catalogUri: \"https://q\", catalogName: w, audience: a, bucket: b}").problems())
            .anyMatch(p -> p.contains("dataProviders.defined[0].id 'lon-cvd' is used by another Data Provider"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAProjectThatIsNotThereIsNotADataProjectOrHasNotTheCatalog(AfonCloud cloud) throws IOException {
        assertThat(invalid(cloud, named("{project: lon, catalogs: [cvd]}")).problems())
            .anyMatch(p -> p.startsWith("dataProviders.named[0].project names lon, which has no file"));
        provider(cloud);
        Files.createDirectories(home.resolve("projects/idp"));
        Files.writeString(home.resolve("projects/idp/project.yaml"), ProjectFixtures.identityExample(cloud).replace("id: idp", "id: idp"));
        assertThat(invalid(cloud, named("{project: idp, catalogs: [cvd]}")).problems()).anyMatch(p -> p.contains("which has the role identity, not data"));
        assertThat(invalid(cloud, named("{project: lon, catalogs: [xyz]}")).problems())
            .anyMatch(p -> p.contains("dataProviders.named[0].catalog 'xyz' is not a catalog of lon, which has [cvd, dbt]"));
        assertThat(invalid(cloud, named("{project: lon, catalogs: [cvd], bucket: b}")).problems())
            .anyMatch(p -> p.contains("dataProviders.named[0].bucket is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnEntryWithNoCatalogsOrWhoseCatalogsAreNotAListOfThem(AfonCloud cloud) throws IOException {
        provider(cloud);
        assertThat(invalid(cloud, named("{project: lon}")).problems()).anyMatch(p -> p.contains("dataProviders.named[0].catalogs must be a non-empty list"));
        assertThat(invalid(cloud, named("{project: lon, catalogs: []}")).problems()).anyMatch(p -> p.contains("dataProviders.named[0].catalogs must be a non-empty list"));
        assertThat(invalid(cloud, named("{project: lon, catalogs: cvd}")).problems()).anyMatch(p -> p.contains("dataProviders.named[0].catalogs must be a non-empty list"));
        assertThat(invalid(cloud, named("{project: lon, catalogs: [cvd, cvd]}")).problems()).anyMatch(p -> p.contains("catalogs[1] 'cvd' is listed twice"));
        assertThat(invalid(cloud, named("{project: lon, catalogs: [cvd, xyz]}")).problems()).anyMatch(p -> p.contains("catalog 'xyz' is not a catalog of lon"));
        assertThat(invalid(cloud, named("{project: lon, catalog: cvd}")).problems()).anyMatch(p -> p.contains("dataProviders.named[0].catalog is not a known key"));
        assertThat(invalid(cloud, named("{project: lon, catalogs: [cvd], id: x}")).problems()).anyMatch(p -> p.contains("dataProviders.named[0].id is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesDataProvidersThatNameNoCatalogOrAreNotNamedOrDefined(AfonCloud cloud) throws IOException {
        provider(cloud);
        assertThat(invalid(cloud, "  named: []").problems()).anyMatch(p -> p.contains("dataProviders names no catalog"));
        assertThat(invalid(cloud, "  other: []").problems()).anyMatch(p -> p.contains("dataProviders.other is not a known key"));
        assertThat(invalid(cloud, "  - {project: lon, catalogs: [cvd]}").problems()).anyMatch(p -> p.contains("dataProviders must be a mapping of named and defined"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesADataProviderThatTrustsAnotherIdentity(AfonCloud cloud) throws IOException {
        provider(cloud);
        Files.writeString(home.resolve("projects/lon/project.yaml"), Files.readString(home.resolve("projects/lon/project.yaml"))
                                                                          .replace("https://id.example.org", "https://other.example.org"));
        assertThat(invalid(cloud, named("{project: lon, catalogs: [cvd]}")).problems())
            .anyMatch(p -> p.contains("lon trusts the identity at https://other.example.org/realms/polaris, and this project uses the one at https://id.example.org"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void saysWhyADataProviderWithNoPolarisDomainCannotBeRead(AfonCloud cloud) throws IOException {
        provider(cloud);
        Files.writeString(home.resolve("config-dev/afon-" + lower(cloud) + ".properties"), "");
        assertThat(invalid(cloud, named("{project: lon, catalogs: [cvd]}")).problems()).anyMatch(p -> p.contains("its Polaris has no domain"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void isLookedUpInTheAfonHomeSoNotByAFileOnItsOwn(AfonCloud cloud) {
        String yaml = ProjectFixtures.example(cloud).replaceAll("(?s)dataProviders:.*?\\nmembers:", "dataProviders:\n" + named("{project: lon, catalogs: [cvd]}") + "\nmembers:");
        assertThatThrownBy(() -> FileProjectProvider.parse(yaml)).isInstanceOf(ProjectException.class)
                                                                .hasMessageContaining("looked up in the projects of the Afon home");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void writesTheResolvedCatalogsInTheCopyOfTheFileThatConfigureKeeps(AfonCloud cloud) throws IOException {
        provider(cloud);
        TreProject project = tre(cloud, named("{project: lon, catalogs: [cvd]}"));
        Path projectFile = home.resolve("projects/xyz-yyyy-nnnn/project.yaml");
        Path config = ProjectEnv.configure(project, home.resolve("config-dev"), home, projectFile);
        // the copy is what the commands in the project's home read, where the Data Providers' projects are not looked up: it holds the catalog in full, as defined
        TreProject copied = (TreProject) new FileProjectProvider(config.resolve("project.yaml")).getProject();
        assertThat(copied.dataProviders()).hasSize(1);
        assertThat(copied.dataProviders().get(0).id()).isEqualTo("lon-cvd");
        assertThat(copied.dataProviders().get(0).catalogUri()).isEqualTo(project.dataProviders().get(0).catalogUri());
        assertThat(copied.dataProviders().get(0).audience()).isEqualTo("provider-lon");
        assertThat(copied.dataProviders().get(0).bucket()).isEqualTo(project.dataProviders().get(0).bucket());
        assertThat(copied.dataProviders().get(0).project()).isNull();
        // the deployer's own file is left as it is
        assertThat(Files.readString(projectFile)).contains("{project: lon, catalogs: [cvd]}");
    }
}
