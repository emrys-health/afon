package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.project.ProjectFixtures.lower;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every test is run on every cloud.
 */
class ProjectNamesTest {

    /** The config a project is made from: the account of each cloud, and what the cloud's create script reads. */
    private static Properties env(String... more) {
        Properties env = new Properties();
        env.setProperty("AZURE_SUBSCRIPTION", "sub-1");
        env.setProperty("AWS_ACCOUNT_ID", "111111111111");
        env.setProperty("GCP_PROJECT_ID", "gcp-proj");
        for (String cloud : new String[]{"AWS", "GCP", "AZURE"}) {
            env.setProperty(cloud + "_CATALOG_IDS", cloud.toLowerCase() + ",x_external");
            env.setProperty(cloud + "_BUCKET_NAME_" + cloud.toLowerCase(), "dev-bucket");
            env.setProperty(cloud + "_BUCKET_NAME_x_external", "dev-bucket-2");
            env.setProperty(cloud + "_GITEA_BUCKET", "dev-gitea");
            env.setProperty(cloud + "_POLARIS_LOG_BUCKET", "dev-logs");
            env.setProperty(cloud + "_POLARIS_DOMAIN_NAME", cloud.toLowerCase() + "-polaris.dev.click");
            env.setProperty(cloud + "_JUPYTER_DOMAIN_NAME", cloud.toLowerCase() + "-jupyter.dev.click");
        }
        for (int i = 0; i + 1 < more.length; i += 2) {
            env.setProperty(more[i], more[i + 1]);
        }
        return env;
    }

    private static Map<String, String> all(AfonProject project, Properties env) {
        Map<String, String> all = new java.util.LinkedHashMap<>();
        ProjectNames.derive(project, env, "dev").values().forEach(all::putAll);
        return all;
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheClusterAfterTheProjectOnEveryCloud(AfonCloud cloud) {
        assertThat(all(ProjectFixtures.tre(ProjectFixtures.example(cloud)), env())).containsEntry("CLUSTER_NAME", "xyz-yyyy-nnnn-cluster");
        assertThat(all(ProjectFixtures.identity(ProjectFixtures.identityExample(cloud)), env())).containsEntry("CLUSTER_NAME", "idp-cluster");
    }

    @Test
    void hashesTheCloudTheAccountAndTheProjectIntoAStableToken() {
        String token = ProjectNames.token(AfonCloud.AZURE, "sub-1", "xyz-yyyy-nnnn");
        assertThat(token).hasSize(ProjectNames.TOKEN_LENGTH).matches("[0-9a-f]+");
        assertThat(ProjectNames.token(AfonCloud.AZURE, "sub-1", "xyz-yyyy-nnnn")).isEqualTo(token);
        // the same project in another account, or another project in the account, has another
        assertThat(ProjectNames.token(AfonCloud.AZURE, "sub-2", "xyz-yyyy-nnnn")).isNotEqualTo(token);
        assertThat(ProjectNames.token(AfonCloud.AZURE, "sub-1", "xyz-yyyy-nnnm")).isNotEqualTo(token);
        assertThat(ProjectNames.token(AfonCloud.GCP, "sub-1", "xyz-yyyy-nnnn")).isNotEqualTo(token);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesWhatIsUniqueInTheWholeCloudAfterTheTokenOfTheProject(AfonCloud cloud) {
        // the project names no Git bucket, so it has its own, not the env's, which projects from it would share
        TreProject project = ProjectFixtures.tre(ProjectFixtures.example(cloud).replace("    gitBucket: xyz-yyyy-nnnn-gitea\n", ""));
        String account = switch (cloud) {
            case AZURE -> "sub-1";
            case AWS -> "111111111111";
            case GCP -> "gcp-proj";
        };
        String token = ProjectNames.token(cloud, account, "xyz-yyyy-nnnn");
        Map<String, String> names = all(project, env());
        String prefix = cloud.name() + "_";
        switch (cloud) {
            case AZURE -> {
                assertThat(names).containsEntry("AZURE_STORAGE_ACCOUNT", "afonstorage" + token).containsEntry("AZURE_KEY_VAULT_NAME", "afon-kv-" + token);
                // an Azure storage account has 3 to 24 lower-case letters and digits, whatever the project id
                assertThat(names.get("AZURE_STORAGE_ACCOUNT")).matches("[a-z0-9]{3,24}");
                assertThat(names.get("AZURE_KEY_VAULT_NAME")).matches("[a-z][a-z0-9-]{2,23}");
                // the containers of the catalogs, Git and the logs are in the account, so they are the project's own
                assertThat(names).doesNotContainKeys("AZURE_BUCKET_NAME_azure", "AZURE_POLARIS_LOG_BUCKET");
            }
            case AWS, GCP -> {
                assertThat(names).containsEntry(prefix + "BUCKET_NAME_" + lower(cloud), "afon-" + token + "-" + lower(cloud))
                                 .containsEntry(prefix + "BUCKET_NAME_x_external", "afon-" + token + "-x-external")
                                 .containsEntry(prefix + "POLARIS_LOG_BUCKET", "afon-" + token + "-polaris-logs");
                assertThat(names).containsEntry(prefix + "GITEA_BUCKET", "afon-" + token + "-gitea");
                assertThat(names.values()).allMatch(v -> v.length() <= 63);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void keepsTheGitBucketTheProjectNames(AfonCloud cloud) {
        TreProject project = ProjectFixtures.tre(ProjectFixtures.example(cloud));
        assertThat(project.create().gitBucket()).isNotNull();
        assertThat(all(project, env())).doesNotContainKey(cloud.name() + "_GITEA_BUCKET");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesNothingTheEnvDoesNotUse(AfonCloud cloud) {
        // no Polaris log bucket in the env: logs are not shipped, and are not turned on by a project
        Properties env = env(cloud.name() + "_POLARIS_LOG_BUCKET", "", cloud.name() + "_GITEA_BUCKET", "");
        TreProject project = ProjectFixtures.tre(ProjectFixtures.example(cloud).replace("    gitBucket: xyz-yyyy-nnnn-gitea\n", ""));
        assertThat(all(project, env)).doesNotContainKeys(cloud.name() + "_POLARIS_LOG_BUCKET", cloud.name() + "_GITEA_BUCKET");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheProjectsDifferentlyInDifferentAccounts(AfonCloud cloud) {
        String account = switch (cloud) {
            case AZURE -> "AZURE_SUBSCRIPTION";
            case AWS -> "AWS_ACCOUNT_ID";
            case GCP -> "GCP_PROJECT_ID";
        };
        TreProject project = ProjectFixtures.tre(ProjectFixtures.example(cloud));
        Map<String, String> here = all(project, env());
        Map<String, String> there = all(project, env(account, "another-account"));
        here.forEach((name, value) -> {
            if (!name.equals("CLUSTER_NAME") && !name.endsWith("_DOMAIN_NAME")) {
                assertThat(there.get(name)).as(name).isNotEqualTo(value);
            }
        });
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void needsTheAccountOfTheCloud(AfonCloud cloud) {
        Properties env = env();
        env.remove(switch (cloud) {
            case AZURE -> "AZURE_SUBSCRIPTION";
            case AWS -> "AWS_ACCOUNT_ID";
            case GCP -> "GCP_PROJECT_ID";
        });
        assertThatThrownBy(() -> all(ProjectFixtures.tre(ProjectFixtures.example(cloud)), env))
            .isInstanceOf(ProjectException.class).hasMessageContaining("is not set in the config the project is made from");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void findsTheProjectOfAGlobalName(AfonCloud cloud) {
        TreProject project = ProjectFixtures.tre(ProjectFixtures.example(cloud));
        String name = ProjectNames.globalNames(project, env()).get(0);
        assertThat(ProjectNames.find(project, env(), name)).isTrue();
        assertThat(ProjectNames.find(project, env(), name.toUpperCase())).isTrue();
        assertThat(ProjectNames.find(project, env(), "another-name")).isFalse();
        assertThat(ProjectNames.find(ProjectFixtures.identity(ProjectFixtures.identityExample(cloud)), env(), name)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void writesTheNamesIntoTheConfigOfTheProjectAndSetsWhatDependsOnThemAgain(AfonCloud cloud, @TempDir Path home) throws IOException {
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\nCLUSTER_NAME=${ENV_NAME}-cluster\n" + ProjectEnvTest.ACCOUNTS);
        String c = lower(cloud);
        Files.writeString(envConfig.resolve("afon-" + c + ".properties"),
                          cloud.name() + "_STORAGE_ACCOUNT=${ENV_NAME}afonstorage\n"
                              + cloud.name() + "_POLARIS_LOG_BUCKET=${" + cloud.name() + "_STORAGE_ACCOUNT}-polaris-logs\n"
                              + cloud.name() + "_CATALOG_IDS=" + c + "\n");
        Path file = ProjectFixtures.writeExample(home.resolve("project.yaml"), cloud);
        ProjectFixtures.writeSecrets(home);
        Path config = ProjectEnv.configure(ProjectFixtures.tre(ProjectFixtures.example(cloud)), envConfig, home, file);
        String common = Files.readString(config.resolve("afon.properties"));
        assertThat(common).contains("CLUSTER_NAME=xyz-yyyy-nnnn-cluster");
        // CLUSTER_NAME is the env's first, then the project's, which wins as it is later
        assertThat(common.indexOf("CLUSTER_NAME=${ENV_NAME}-cluster")).isLessThan(common.indexOf("CLUSTER_NAME=xyz-yyyy-nnnn-cluster"));
        if (cloud == AfonCloud.AZURE) {
            String azure = Files.readString(config.resolve("afon-azure.properties"));
            assertThat(azure).contains("AZURE_STORAGE_ACCOUNT=afonstorage" + ProjectNames.token(cloud, "sub-1", "xyz-yyyy-nnnn"));
            // the property set in terms of the account is written again, after it, so that a shell sourcing the file sees the new account
            assertThat(azure.lastIndexOf("AZURE_POLARIS_LOG_BUCKET=${AZURE_STORAGE_ACCOUNT}-polaris-logs"))
                .isGreaterThan(azure.lastIndexOf("AZURE_STORAGE_ACCOUNT=afonstorage"));
        }
    }

    private static String withId(AfonCloud cloud, String id) {
        return ProjectFixtures.example(cloud).replace("xyz-yyyy-nnnn", id);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnIdThatMakesAClusterNameTooLongForTheCloud(AfonCloud cloud) {
        // full-<id>-cluster is 13 characters and the id: 40 is GKE's limit, 63 AKS's, 100 EKS's
        int limit = switch (cloud) {
            case GCP -> 40;
            case AZURE -> 63;
            case AWS -> 100;
        };
        String fits = "a".repeat(limit - "full--cluster".length());
        assertThat(ProjectFixtures.tre(withId(cloud, fits.length() > 30 ? "a".repeat(30) : fits)).id().value()).isNotEmpty();
        if (limit - "full--cluster".length() >= 30) {
            return;
        }
        assertThatThrownBy(() -> FileProjectProvider.parse(withId(cloud, "a".repeat(limit - "full--cluster".length() + 1))))
            .isInstanceOf(ProjectException.class).hasMessageContaining("is too long for " + lower(cloud), "over the limit of " + limit);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesADomainThatAnotherProjectOfTheCloudHas(AfonCloud cloud) {
        TreProject mine = ProjectFixtures.tre(ProjectFixtures.example(cloud));
        // the same domains, as the file has it, under another project id
        TreProject other = ProjectFixtures.tre(ProjectFixtures.example(cloud).replaceFirst("id: xyz-yyyy-nnnn", "id: other-project"));
        assertThat(ProjectNames.domainCollisions(mine, java.util.List.of(mine, other)))
            .anyMatch(p -> p.contains("jupyter.xyz-yyyy-nnnn.example.org") && p.contains("also that of other-project"));
        // the domain of another cloud's project is another's
        AfonCloud elsewhere = cloud == AfonCloud.AWS ? AfonCloud.GCP : AfonCloud.AWS;
        TreProject far = ProjectFixtures.tre(ProjectFixtures.example(elsewhere).replaceFirst("id: xyz-yyyy-nnnn", "id: far-project"));
        assertThat(ProjectNames.domainCollisions(mine, java.util.List.of(far))).isEmpty();
        // and a project does not collide with itself
        assertThat(ProjectNames.domainCollisions(mine, java.util.List.of(mine))).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void putsTheDomainsOfTheTreUnderTheProjectInPlaceOfTheEnvs(AfonCloud cloud) {
        // azure-jupyter.dev.click of the env is azure-jupyter.<project>.dev.click, which the zone of dev.click delegates, as it does the env's
        String c = lower(cloud);
        Map<String, String> names = all(ProjectFixtures.tre(ProjectFixtures.example(cloud)), env());
        assertThat(names).containsEntry(cloud.name() + "_POLARIS_DOMAIN_NAME", c + "-polaris.xyz-yyyy-nnnn.dev.click")
                         .containsEntry(cloud.name() + "_JUPYTER_DOMAIN_NAME", c + "-jupyter.xyz-yyyy-nnnn.dev.click");
        // the central identity serves the identity's domain, which is not a project's
        assertThat(all(ProjectFixtures.identity(ProjectFixtures.identityExample(cloud)), env())).doesNotContainKeys(cloud.name() + "_POLARIS_DOMAIN_NAME",
                                                                                                                    cloud.name() + "_JUPYTER_DOMAIN_NAME");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesNoDomainTheEnvHasNone(AfonCloud cloud) {
        Properties env = env(cloud.name() + "_POLARIS_DOMAIN_NAME", "");
        assertThat(all(ProjectFixtures.tre(ProjectFixtures.example(cloud)), env)).doesNotContainKey(cloud.name() + "_POLARIS_DOMAIN_NAME")
                                                                                .containsKey(cloud.name() + "_JUPYTER_DOMAIN_NAME");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void cannotPutAProjectUnderARegisteredDomain(AfonCloud cloud) {
        // emryspolaris.click has no parent domain: xyz.click would be under the top level domain, which nobody here owns
        Properties env = env(cloud.name() + "_JUPYTER_DOMAIN_NAME", "emryspolaris.click");
        assertThatThrownBy(() -> all(ProjectFixtures.tre(ProjectFixtures.example(cloud)), env))
            .isInstanceOf(ProjectException.class).hasMessageContaining("has no parent domain", "stacks.tre.domains");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void aDomainOfTheProjectFileWinsOverTheDerivedOne(AfonCloud cloud, @TempDir Path home) throws IOException {
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n" + ProjectEnvTest.ACCOUNTS);
        Files.writeString(envConfig.resolve("afon-" + lower(cloud) + ".properties"),
                          cloud.name() + "_JUPYTER_DOMAIN_NAME=" + lower(cloud) + "-jupyter.dev.click\n");
        Path file = ProjectFixtures.writeExample(home.resolve("project.yaml"), cloud);
        ProjectFixtures.writeSecrets(home);
        Path config = ProjectEnv.configure(ProjectFixtures.tre(ProjectFixtures.example(cloud)), envConfig, home, file);
        String properties = Files.readString(config.resolve("afon-" + lower(cloud) + ".properties"));
        // the example names its Jupyter domain, which is the one written; the Polaris domain is derived
        assertThat(properties).contains(cloud.name() + "_JUPYTER_DOMAIN_NAME=jupyter.xyz-yyyy-nnnn.example.org")
                              .doesNotContain(lower(cloud) + "-jupyter.xyz-yyyy-nnnn.dev.click");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void putsTheOidcDomainOfTheIdentityUnderItsProjectAsTheDomainsOfATreAre(AfonCloud cloud) {
        // azure-oidc.dev.click of the env is azure-oidc.idp.dev.click: the env may have a Keycloak of its own at its domain
        String c = lower(cloud);
        Properties env = env(cloud.name() + "_OIDC_DOMAIN_NAME", c + "-oidc.dev.click");
        assertThat(all(ProjectFixtures.identity(ProjectFixtures.identityExample(cloud)), env))
            .containsEntry(cloud.name() + "_OIDC_DOMAIN_NAME", c + "-oidc.idp.dev.click");
        // and the TRE of a project leaves the OIDC domain, which is the identity's, alone
        assertThat(all(ProjectFixtures.tre(ProjectFixtures.example(cloud)), env)).doesNotContainKey(cloud.name() + "_OIDC_DOMAIN_NAME");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheOidcDomainOfTheEnvForAllCloudsWhenTheCloudHasNoneOfItsOwn(AfonCloud cloud) {
        Properties env = env("OIDC_DOMAIN_NAME", "oidc.dev.click");
        assertThat(all(ProjectFixtures.identity(ProjectFixtures.identityExample(cloud)), env))
            .containsEntry(cloud.name() + "_OIDC_DOMAIN_NAME", "oidc.idp.dev.click");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void leavesTheOidcDomainOfTheEnvAsItIsWhenItHasNoParentDomainOrNone(AfonCloud cloud) {
        assertThat(all(ProjectFixtures.identity(ProjectFixtures.identityExample(cloud)), env("OIDC_DOMAIN_NAME", "dev.click")))
            .doesNotContainKey(cloud.name() + "_OIDC_DOMAIN_NAME");
        assertThat(all(ProjectFixtures.identity(ProjectFixtures.identityExample(cloud)), env()))
            .doesNotContainKey(cloud.name() + "_OIDC_DOMAIN_NAME");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void givesTheIdentityADatabaseOfItsOwnSoThatItDoesNotShareTheRealmOfTheEnvsKeycloak(AfonCloud cloud) {
        var identity = ProjectFixtures.identity(ProjectFixtures.identityExample(cloud));
        Properties env = env("KEYCLOAK_DB_URL", "jdbc:postgresql://db.example.org:5432/keycloak-dev");
        assertThat(ProjectNames.derive(identity, env, "dev").get("secret.properties"))
            .containsOnlyKeys("KEYCLOAK_DB_URL").containsEntry("KEYCLOAK_DB_URL", "jdbc:postgresql://db.example.org:5432/keycloak-idp");
        // the parameters of the URL are kept
        assertThat(ProjectNames.derive(identity, env("KEYCLOAK_DB_URL", "jdbc:postgresql://db.example.org:5432/keycloak?sslmode=require"), "dev")
                               .get("secret.properties")).containsEntry("KEYCLOAK_DB_URL", "jdbc:postgresql://db.example.org:5432/keycloak-idp?sslmode=require");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesNoDatabaseForATreOrWhenTheEnvHasNoneToDeriveFrom(AfonCloud cloud) {
        Properties env = env("KEYCLOAK_DB_URL", "jdbc:postgresql://db.example.org:5432/keycloak");
        // a TRE uses the identity's Keycloak, with no database of its own
        assertThat(ProjectNames.derive(ProjectFixtures.tre(ProjectFixtures.example(cloud)), env, "dev")).doesNotContainKey("secret.properties");
        var identity = ProjectFixtures.identity(ProjectFixtures.identityExample(cloud));
        assertThat(ProjectNames.derive(identity, env(), "dev")).doesNotContainKey("secret.properties");
        assertThat(ProjectNames.derive(identity, env("KEYCLOAK_DB_URL", "not-a-url"), "dev")).doesNotContainKey("secret.properties");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void putsThePolarisDomainOfADataProjectUnderItsProjectUnlessItsFileGivesOne(AfonCloud cloud) {
        String c = lower(cloud);
        Properties env = env(cloud.name() + "_POLARIS_DOMAIN_NAME", c + "-polaris.dev.click");
        var data = ProjectFixtures.data(ProjectFixtures.dataExample(cloud));
        assertThat(all(data, env)).containsEntry(cloud.name() + "_POLARIS_DOMAIN_NAME", c + "-polaris.lon.dev.click");
        // what the file says is for the config of the project to take, so nothing is derived for it
        var named = ProjectFixtures.data(ProjectFixtures.dataExample(cloud) + "stacks:\n  data: {domain: polaris.lon.example.org}\n");
        assertThat(all(named, env)).doesNotContainKey(cloud.name() + "_POLARIS_DOMAIN_NAME");
        assertThat(ProjectNames.domainCollisions(named, java.util.List.of(ProjectFixtures.data(ProjectFixtures.dataExample(cloud)
            .replace("id: lon", "id: nw") + "stacks:\n  data: {domain: polaris.lon.example.org}\n")))).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToDeriveThePolarisDomainOfADataProjectFromOneWithNoParent(AfonCloud cloud) {
        Properties env = env(cloud.name() + "_POLARIS_DOMAIN_NAME", "polaris.click");
        assertThatThrownBy(() -> all(ProjectFixtures.data(ProjectFixtures.dataExample(cloud)), env)).isInstanceOf(ProjectException.class)
                                                                                                    .hasMessageContaining("stacks.data.domain");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void givesADataProjectCatalogsOfItsOwnInPlaceOfTheEnvs(AfonCloud cloud) {
        String p = cloud.name() + "_";
        var derived = ProjectNames.derive(ProjectFixtures.data(ProjectFixtures.dataExample(cloud)), env(), "dev");
        Map<String, String> catalogs = derived.get("catalogs-" + lower(cloud) + ".properties");
        assertThat(catalogs).containsEntry(p + "CATALOG_IDS", "cvd,dbt")
                            .containsEntry(p + "CATALOG_NAME_cvd", "cvd").containsEntry(p + "CATALOG_TYPE_cvd", "internal")
                            .containsEntry(p + "CATALOG_NAME_dbt", "dbt").containsEntry(p + "CATALOG_TYPE_dbt", "internal");
        String token = ProjectNames.token(cloud, ProjectNames.account(cloud, env(), "dev"), "lon");
        if (cloud == AfonCloud.AZURE) {
            // a container of the project's storage account
            assertThat(catalogs).containsEntry(p + "BUCKET_NAME_cvd", "cvd").containsEntry(p + "BUCKET_NAME_dbt", "dbt");
        } else {
            assertThat(catalogs).containsEntry(p + "BUCKET_NAME_cvd", "afon-" + token + "-cvd").containsEntry(p + "BUCKET_NAME_dbt", "afon-" + token + "-dbt");
        }
        // and none of the env's, which have their own ids
        assertThat(catalogs).doesNotContainKeys(p + "BUCKET_NAME_" + lower(cloud), p + "BUCKET_NAME_x_external");
    }
}
