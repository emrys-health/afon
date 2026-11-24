package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.ENV_NAME;
import static health.emrys.afon.config.AfonProperty.OIDC_ISSUER_URL;
import static health.emrys.afon.config.AfonProperty.PROJECT_ID;
import static health.emrys.afon.project.ProjectStackFixtures.lower;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every test is run on every cloud, see {@link ProjectStackFixtures}.
 */
class ProjectStackEnvTest {

    @TempDir
    Path home;

    /** The account of the config the project stack is made from, on each cloud: the names of the project stack's resources depend on it. */
    static final String ACCOUNTS = "AZURE_SUBSCRIPTION=sub-1\nAWS_ACCOUNT_ID=111111111111\nGCP_PROJECT_ID=gcp-proj\n";

    /** The example project stack on a cloud, in a file, with the config of the env it is made from, which has the properties of that cloud. */
    private record OnCloud(ProjectStack projectStack, Path projectFile, Path envConfig) {
    }

    private OnCloud on(AfonCloud cloud) throws IOException {
        Path file = ProjectStackFixtures.writeExample(home.resolve("project-" + lower(cloud) + ".yaml"), cloud);
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS + "PROJECT_ID=\nOIDC_ISSUER_URL=https://[OIDC_DOMAIN_NAME]/realms/polaris");
        Files.writeString(env.resolve("afon-" + lower(cloud) + ".properties"), cloud.name() + "_REGION=a-region\n" + cloud.name() + "_KEYCLOAK_EXTERNAL=false\n");
        Files.writeString(env.resolve("secret.properties"), "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=s3cret\n");
        Files.writeString(env.resolve("catalogs.properties"), "");
        return new OnCloud(new FileProjectStackProvider(file).getProjectStack(), file, env);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheProjectEnvsAfterTheProject(AfonCloud cloud) throws IOException {
        ProjectStack projectStack = on(cloud).projectStack();
        assertThat(ProjectStackEnv.configDir(home, projectStack)).isEqualTo(home.resolve("config-xyz-yyyy-nnnn"));
        assertThat(ProjectStackEnv.cdktfDir(home, projectStack)).isEqualTo(home.resolve("cdktf-xyz-yyyy-nnnn"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesTheSmokeMemberTheSmokeTestsSignInAsFromTheFile(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        Files.writeString(setup.projectFile(), Files.readString(setup.projectFile())
                                                   + "smokeMembers:\n  - Grace@example.org\n  - henry@example.org\n");

        var derived = ProjectStackEnv.derive(new FileProjectStackProvider(setup.projectFile()).getProjectStack());

        assertThat(derived.get("afon.properties")).containsEntry("SMOKE_MEMBER", "grace@example.org");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesTheCommonAndTheCloudProperties(AfonCloud cloud) throws IOException {
        String prefix = cloud.name() + "_";
        String file = "afon-" + lower(cloud) + ".properties";
        var derived = ProjectStackEnv.derive(on(cloud).projectStack());
        // and the catalogs of its Data Providers, in the catalogs file of the cloud of their storage, which is its own here
        assertThat(derived.keySet()).containsExactly("afon.properties", file, "catalogs-" + lower(cloud) + ".properties");
        assertThat(derived.get("afon.properties")).containsEntry("PROJECT_ID", "xyz-yyyy-nnnn")
            .containsEntry("OIDC_AUDIENCE", "xyz-yyyy-nnnn-tre").containsEntry("OIDC_GIT_AUDIENCE", "xyz-yyyy-nnnn-git")
            .containsEntry("OIDC_PROJECT_CLAIM", "project").containsEntry("OIDC_USER_CLAIM", "polaris_principal")
            .doesNotContainKey("SMOKE_MEMBER")
            .containsEntry("GIT_DOMAIN_NAME", "git.xyz-yyyy-nnnn.example.org").containsEntry("TRE_TEARDOWN_TIME", "22:00")
            .containsEntry("TRE_TEARDOWN_TIME_ZONE", "Europe/London");
        // the identity is at the host of its issuer, whatever the env's own OIDC domain is
        assertThat(derived.get(file)).containsEntry(prefix + "OIDC_DOMAIN_NAME", "id.example.org");
        assertThat(derived.get(file)).containsEntry(prefix + "KEYCLOAK_EXTERNAL", "true")
            .containsEntry(prefix + "AUDIT_BUCKET", "xyz-yyyy-nnnn-audit").containsEntry(prefix + "EGRESS_IP", "203.0.113.10").containsEntry(prefix + "GITEA_BUCKET", "xyz-yyyy-nnnn-gitea")
            .containsEntry(prefix + "JUPYTER_DOMAIN_NAME", "jupyter.xyz-yyyy-nnnn.example.org");
        // only what belongs to the project stack's cloud goes in its file
        assertThat(derived.get(file).keySet()).allMatch(name -> name.startsWith(prefix));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresFromTheEnvWithTheDerivedPropertiesWrittenOverIt(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());

        assertThat(config).isEqualTo(home.resolve("config-xyz-yyyy-nnnn"));
        assertThat(home.resolve("cdktf-xyz-yyyy-nnnn")).isDirectory();
        assertThat(config.resolve("secret.properties")).content().startsWith("KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=s3cret\n").contains("OAUTH2_CLIENT_SECRET=secret-of-xyz-yyyy-nnnn");
        assertThat(config.resolve("project.yaml")).hasSameTextualContentAs(setup.projectFile());
        // the env is left as it was
        assertThat(setup.envConfig().resolve("afon.properties")).content().doesNotContain("Derived");

        AfonPropertyProvider properties = new FilePropertyProvider(config.toString());
        assertThat(properties.getValue(ENV_NAME)).isEqualTo("dev");
        assertThat(properties.getValue(PROJECT_ID)).isEqualTo("xyz-yyyy-nnnn");
        assertThat(properties.getValue(OIDC_ISSUER_URL)).isEqualTo("https://id.example.org/realms/polaris");
        assertThat(properties.getValue(AfonProperty.valueOf(cloud.name() + "_KEYCLOAK_EXTERNAL"))).isEqualTo("true");
        assertThat(properties.getValue(AfonProperty.valueOf(cloud.name() + "_REGION"))).isEqualTo("a-region");
        assertThat(properties.getCloudValue(cloud, AfonCloudProperty.AUDIT_BUCKET)).isEqualTo("xyz-yyyy-nnnn-audit");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuringAgainReplacesWhatItMadeAndKeepsNothingStale(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        ProjectStackFixtures.writeSecrets(home);
        ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());
        assertThat(Files.readString(config.resolve("afon.properties")).split("Derived from project.yaml", -1)).hasSize(2);
        Files.writeString(setup.envConfig().resolve("afon-" + lower(cloud) + ".properties"), cloud.name() + "_REGION=another-region\n");
        ProjectStackFixtures.writeSecrets(home);
        ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());
        assertThat(new FilePropertyProvider(config.toString()).getValue(AfonProperty.valueOf(cloud.name() + "_REGION"))).isEqualTo("another-region");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void makesAPropertiesFileTheEnvLacks(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        String file = "afon-" + lower(cloud) + ".properties";
        Files.delete(setup.envConfig().resolve(file));
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());
        assertThat(config.resolve(file)).content().contains(cloud.name() + "_AUDIT_BUCKET=xyz-yyyy-nnnn-audit");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesNothingOfGitFromAProjectWithNoGitOfItsOwn(AfonCloud cloud) {
        String yaml = ProjectStackFixtures.example(cloud).replace("    gitBucket: xyz-yyyy-nnnn-gitea\n", "")
                                     .replace("  git:\n    domain: git.xyz-yyyy-nnnn.example.org\n", "");
        var derived = ProjectStackEnv.derive(FileProjectStackProvider.parse(yaml));
        assertThat(derived.get("afon.properties")).doesNotContainKeys("GIT_DOMAIN_NAME", "OIDC_GIT_AUDIENCE").containsKey("OIDC_AUDIENCE");
        assertThat(derived.values().stream().flatMap(m -> m.keySet().stream())).doesNotContain(cloud.name() + "_GITEA_BUCKET")
            .contains(cloud.name() + "_AUDIT_BUCKET");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresTheCatalogsOfTheDataProvidersAsCatalogsFromAnotherSource(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());

        var catalogs = new health.emrys.afon.config.AfonCatalogs(new FilePropertyProvider(config.toString()));
        var fromDataProviders = catalogs.bySource(health.emrys.afon.config.AfonCatalogs.Source.DATA_PROVIDER);
        assertThat(fromDataProviders).extracting(health.emrys.afon.config.AfonCatalog::id).containsExactly("data1", "data2");
        var first = fromDataProviders.get(0);
        assertThat(first.catalogUri()).isEqualTo("https://polaris.data1.example/api/catalog");
        assertThat(first.name()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(first.audience()).isEqualTo("provider-data1");
        assertThat(first.bucketName()).isEqualTo("data1-xyz-yyyy-nnnn");
        assertThat(first.cloud()).isEqualTo(cloud);
        // the ids are in the list of the cloud's catalogs, where the others are, and in its catalogs file, not in afon.properties
        assertThat(config.resolve("catalogs-" + lower(cloud) + ".properties")).content().contains(cloud.name() + "_CATALOG_IDS=data1,data2");
        assertThat(config.resolve("afon.properties")).content().doesNotContain("CATALOG_SOURCE");
        // what the stack makes and queries is what the env's config says
        assertThat(catalogs.all()).extracting(health.emrys.afon.config.AfonCatalog::id).doesNotContain("data1", "data2");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void keepsTheCatalogIdsThatTheEnvListsAndNamesTheOnesOfTheDataProvidersWithUnderscores(AfonCloud cloud) {
        String yaml = ProjectStackFixtures.example(cloud).replace("id: data1", "id: lon-cvd");
        java.util.Properties env = new java.util.Properties();
        env.setProperty("AZURE_SUBSCRIPTION", "sub-1");
        env.setProperty("AWS_ACCOUNT_ID", "111111111111");
        env.setProperty("GCP_PROJECT_ID", "gcp-proj");
        env.setProperty(cloud.name() + "_CATALOG_IDS", "mine,other");
        var catalogs = ProjectStackEnv.derive(ProjectStackFixtures.tre(yaml), null, null, env).get("catalogs-" + lower(cloud) + ".properties");
        assertThat(catalogs).containsEntry(cloud.name() + "_CATALOG_IDS", "mine,other,lon_cvd,data2")
                            .containsEntry(cloud.name() + "_CATALOG_NAME_lon_cvd", "xyz-yyyy-nnnn")
                            .containsEntry(cloud.name() + "_CATALOG_SOURCE_lon_cvd", "data-provider")
                            .containsEntry(cloud.name() + "_CATALOG_AUDIENCE_lon_cvd", "provider-data1");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void writesNoDataProviderForTheIdentityNorForADataProject(AfonCloud cloud) {
        assertThat(ProjectStackEnv.derive(ProjectStackFixtures.identity(ProjectStackFixtures.identityExample(cloud))).values().stream().flatMap(m -> m.keySet().stream()))
            .noneMatch(k -> k.contains("CATALOG_SOURCE"));
        assertThat(ProjectStackEnv.derive(ProjectStackFixtures.data(ProjectStackFixtures.dataExample(cloud))).values().stream().flatMap(m -> m.keySet().stream()))
            .noneMatch(k -> k.contains("CATALOG_SOURCE"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesTheNodePoolOfTheClusterAndLeavesItToTheEnvWhenTheProjectNamesNone(AfonCloud cloud) {
        String file = "afon-" + lower(cloud) + ".properties";
        assertThat(ProjectStackEnv.derive(ProjectStackFixtures.tre(ProjectStackFixtures.example(cloud))).get(file).keySet())
            .noneMatch(k -> k.endsWith("_NODE_POOL"));
        if (cloud == AfonCloud.AWS) {
            return;
        }
        String pool = cloud == AfonCloud.AZURE ? "vmSize: Standard_D2s_v3, nodeCount: 2" : "machineType: e2-standard-4, nodeCount: 2";
        String property = cloud == AfonCloud.AZURE ? "AZURE_AKS_NODE_POOL" : "GCP_GKE_NODE_POOL";
        String expected = cloud == AfonCloud.AZURE ? "vmSize:Standard_D2s_v3,nodeCount:2" : "machineType:e2-standard-4,nodeCount:2";
        assertThat(ProjectStackEnv.derive(ProjectStackFixtures.tre(ProjectStackFixtures.example(cloud) + "cluster: {nodePool: {" + pool + "}}\n")).get(file))
            .containsEntry(property, expected);
        assertThat(ProjectStackEnv.derive(ProjectStackFixtures.identity(ProjectStackFixtures.identityExample(cloud) + "cluster: {nodePool: {" + pool + "}}\n")).get(file))
            .containsEntry(property, expected);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheKubeconfigOfTheIdentityProjectsClusterForTheKeycloakItConfigures(AfonCloud cloud, @TempDir Path home) throws IOException {
        Path envConfig = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(envConfig.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS);
        String yaml = ProjectStackFixtures.example(cloud).replace("identity:\n", "identity:\n  projectStack: idp\n");
        Path file = Files.createDirectories(home.resolve("projects/xyz-yyyy-nnnn")).resolve("project.yaml");
        Files.writeString(file, yaml);
        // the identity project stack is made first
        ProjectStackFixtures.writeSecrets(home);
        assertThatThrownBy(() -> ProjectStackEnv.configure(ProjectStackFixtures.tre(yaml), envConfig, home, file))
            .isInstanceOf(ProjectStackException.class).hasMessageContaining("identity.projectStack names idp, which has no file");
        Path identityFile = Files.createDirectories(home.resolve("projects/idp")).resolve("project.yaml");
        Files.writeString(identityFile, ProjectStackFixtures.identityExample(cloud));
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(ProjectStackFixtures.tre(yaml), envConfig, home, file);
        assertThat(config.resolve("afon.properties")).content()
            .contains("KEYCLOAK_KUBECONFIG=" + home.resolve("cdktf-idp/kube/" + lower(cloud) + "-keycloak.config"));
        // a project stack that names none has none
        ProjectStackFixtures.writeSecrets(home);
        Path other = ProjectStackEnv.configure(ProjectStackFixtures.tre(ProjectStackFixtures.example(cloud).replace("xyz-yyyy-nnnn", "abc-def")), envConfig, home, file);
        assertThat(other.resolve("afon.properties")).content().doesNotContain("KEYCLOAK_KUBECONFIG");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void leavesTheEgressIpToTheEnvWhenTheProjectNamesNone(AfonCloud cloud) {
        String yaml = ProjectStackFixtures.example(cloud).replace("  egressIp: 203.0.113.10\n", "");
        var derived = ProjectStackEnv.derive(FileProjectStackProvider.parse(yaml));
        assertThat(derived.values().stream().flatMap(m -> m.keySet().stream())).doesNotContain(cloud.name() + "_EGRESS_IP");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAValueAShellCouldNotSourceSafely(AfonCloud cloud) {
        ProjectStack unsafe = ProjectStackFixtures.tre(ProjectStackFixtures.example(cloud).replace("git.xyz-yyyy-nnnn.example.org", "x; rm -rf ~"));
        assertThatThrownBy(() -> ProjectStackEnv.derive(unsafe)).isInstanceOf(ProjectStackException.class).hasMessageContaining("GIT_DOMAIN_NAME");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesTheConfigOfTheProjectItselfAsItsEnv(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        Path own = Files.createDirectories(ProjectStackEnv.configDir(home, setup.projectStack()));
        ProjectStackFixtures.writeSecrets(home);
        assertThatThrownBy(() -> ProjectStackEnv.configure(setup.projectStack(), own, home, setup.projectFile()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is that of the project stack itself");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheConfigOfAnEnvButNeverAPath(AfonCloud cloud) {
        assertThat(ProjectStackEnv.envConfigDir(home, "emrys2")).isEqualTo(home.resolve("config-emrys2"));
        assertThatThrownBy(() -> ProjectStackEnv.envConfigDir(home, "../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectStackEnv.envConfigDir(home, "a/b")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAConfigThatIsNotADir(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        ProjectStackFixtures.writeSecrets(home);
        assertThatThrownBy(() -> ProjectStackEnv.configure(setup.projectStack(), home.resolve("missing"), home, setup.projectFile()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void derivesOnlyWhatTheIdentityProjectDeploysItselfWith(AfonCloud cloud) {
        String prefix = cloud.name() + "_";
        var derived = ProjectStackEnv.derive(ProjectStackFixtures.identity(ProjectStackFixtures.identityExample(cloud)));
        assertThat(derived.get("afon.properties")).containsOnlyKeys("PROJECT_ID").containsEntry("PROJECT_ID", "idp");
        // the project stack's own egress IP, which the identity's stack pins, as it is deployed by a project stack, and the domain; and no external identity
        assertThat(derived.get("afon-" + lower(cloud) + ".properties")).containsOnlyKeys(prefix + "EGRESS_IP", prefix + "OIDC_DOMAIN_NAME")
            .containsEntry(prefix + "EGRESS_IP", "203.0.113.20").containsEntry(prefix + "OIDC_DOMAIN_NAME", "oidc.example.org");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresTheIdentityProjectFromTheEnvLikeAnyOther(AfonCloud cloud) throws IOException {
        Path file = home.resolve("identity.yaml");
        Files.writeString(file, ProjectStackFixtures.identityExample(cloud));
        ProjectStack projectStack = new FileProjectStackProvider(file).getProjectStack();
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS);
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(projectStack, env, home, file);
        assertThat(config).isEqualTo(home.resolve("config-idp"));
        assertThat(home.resolve("cdktf-idp")).isDirectory();
        AfonPropertyProvider properties = new FilePropertyProvider(config.toString());
        assertThat(properties.getValue(PROJECT_ID)).isEqualTo("idp");
        assertThat(properties.getCloudValue(cloud, AfonCloudProperty.EGRESS_IP)).isEqualTo("203.0.113.20");
        assertThat(properties.getOidcDomainName(cloud)).isEqualTo("oidc.example.org");
    }

    private AfonPropertyProvider configuredIdentity(AfonCloud cloud, String yaml, String envProperties) throws IOException {
        Path file = home.resolve("identity.yaml");
        Files.writeString(file, yaml);
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS + envProperties);
        ProjectStackFixtures.writeSecrets(home);
        return new FilePropertyProvider(ProjectStackEnv.configure(new FileProjectStackProvider(file).getProjectStack(), env, home, file).toString());
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void deploysTheIdentityProjectAtADomainUnderItsProjectUnlessItsFileGivesOne(AfonCloud cloud) throws IOException {
        String example = ProjectStackFixtures.identityExample(cloud);
        String withoutDomain = example.replace("stacks:\n  identity:\n    domain: oidc.example.org\n", "");
        String envDomain = cloud.name() + "_OIDC_DOMAIN_NAME=" + lower(cloud) + "-oidc.dev.click\n";
        // the env's azure-oidc.dev.click is azure-oidc.idp.dev.click for the project stack that deploys the identity
        assertThat(configuredIdentity(cloud, withoutDomain, "OIDC_DOMAIN_NAME=\n" + envDomain).getOidcDomainName(cloud))
            .isEqualTo(lower(cloud) + "-oidc.idp.dev.click");
        // and what the file says wins
        assertThat(configuredIdentity(cloud, example, "OIDC_DOMAIN_NAME=\n" + envDomain).getOidcDomainName(cloud)).isEqualTo("oidc.example.org");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresTheIdentityProjectWithADatabaseOfItsOwn(AfonCloud cloud) throws IOException {
        Path file = home.resolve("identity.yaml");
        Files.writeString(file, ProjectStackFixtures.identityExample(cloud));
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS + "POLARIS_DB_HOST=db.example.org\n");
        Files.writeString(env.resolve("secret.properties"), "KEYCLOAK_DB_URL=jdbc:postgresql://${POLARIS_DB_HOST}:5432/keycloak-dev?sslmode=require\n");
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(new FileProjectStackProvider(file).getProjectStack(), env, home, file);
        // the env's database is left to the env, and the project stack's is its own: the last line of the file wins, as it does when it is sourced
        assertThat(Files.readString(env.resolve("secret.properties"))).doesNotContain("keycloak-idp");
        assertThat(Files.readString(config.resolve("secret.properties")))
            .contains("KEYCLOAK_DB_URL=jdbc:postgresql://db.example.org:5432/keycloak-idp?sslmode=require");
        assertThat(new FilePropertyProvider(config.toString()).getValue(AfonProperty.KEYCLOAK_DB_URL))
            .isEqualTo("jdbc:postgresql://db.example.org:5432/keycloak-idp?sslmode=require");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void configuresADataProjectWithItsCatalogsAndItsPolarisDomain(AfonCloud cloud) throws IOException {
        Path file = home.resolve("data.yaml");
        Files.writeString(file, ProjectStackFixtures.dataExample(cloud));
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS);
        Files.writeString(env.resolve("afon-" + lower(cloud) + ".properties"), cloud.name() + "_POLARIS_DOMAIN_NAME=" + lower(cloud) + "-polaris.dev.click\n");
        Files.writeString(env.resolve("catalogs.properties"), "");
        Files.writeString(env.resolve("catalogs-" + lower(cloud) + ".properties"), cloud.name() + "_CATALOG_IDS=" + lower(cloud) + "\n"
            + cloud.name() + "_CATALOG_NAME_" + lower(cloud) + "=" + lower(cloud) + "\n" + cloud.name() + "_CATALOG_TYPE_" + lower(cloud) + "=internal\n");
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(new FileProjectStackProvider(file).getProjectStack(), env, home, file);
        assertThat(config).isEqualTo(home.resolve("config-lon"));
        AfonPropertyProvider properties = new FilePropertyProvider(config.toString());
        assertThat(properties.getCloudValue(cloud, AfonCloudProperty.POLARIS_DOMAIN_NAME)).isEqualTo(lower(cloud) + "-polaris.lon.dev.click");
        assertThat(properties.getCloudValue(cloud, AfonCloudProperty.EGRESS_IP)).isEqualTo("203.0.113.40");
        // it trusts the central identity, which is at the host of its issuer: its OIDC domain, as the config reads the issuer as being at it
        assertThat(properties.getOidcDomainName(cloud)).isEqualTo("id.example.org");
        assertThat(properties.getOidcIssuerUrl(cloud)).isEqualTo("https://id.example.org/realms/polaris");
        assertThat(properties.getValue(AfonProperty.OIDC_ACR)).isEqualTo("mfa");
        assertThat(properties.getValue(AfonProperty.OIDC_AUDIENCE)).isEqualTo("provider-lon");
        // the catalogs are the project stack's: the env's is no longer one of them
        assertThat(new health.emrys.afon.config.AfonCatalogs(properties).all()).extracting(health.emrys.afon.config.AfonCatalog::id)
                                                                                  .filteredOn(id -> cloudOf(id, cloud)).containsExactly("cvd", "dbt");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void aDataProviderHoldsOnlyItsOwnCatalogsNotThoseThatTheEnvListsForTheOtherClouds(AfonCloud cloud) throws IOException {
        Path file = home.resolve("data.yaml");
        Files.writeString(file, ProjectStackFixtures.dataExample(cloud));
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS);
        Files.writeString(env.resolve("catalogs.properties"), "");
        for (AfonCloud each : AfonCloud.values()) {
            Files.writeString(env.resolve("afon-" + lower(each) + ".properties"), each.name() + "_POLARIS_DOMAIN_NAME=" + lower(each) + "-polaris.dev.click\n");
            Files.writeString(env.resolve("catalogs-" + lower(each) + ".properties"), each.name() + "_CATALOG_IDS=" + lower(each) + "," + lower(each) + "_external\n"
                + each.name() + "_CATALOG_TYPE_" + lower(each) + "=internal\n" + each.name() + "_CATALOG_TYPE_" + lower(each) + "_external=external\n");
        }
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(new FileProjectStackProvider(file).getProjectStack(), env, home, file);
        var catalogs = new health.emrys.afon.config.AfonCatalogs(new FilePropertyProvider(config.toString()));
        assertThat(catalogs.all()).extracting(health.emrys.afon.config.AfonCatalog::id).containsExactly("cvd", "dbt");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void leavesTheCatalogsOfTheOtherCloudsOfAnEnvToTheProjectsThatAreNotDataProjects(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        for (AfonCloud each : AfonCloud.values()) {
            Files.writeString(setup.envConfig().resolve("catalogs-" + lower(each) + ".properties"), each.name() + "_CATALOG_IDS=" + lower(each) + "\n"
                + each.name() + "_CATALOG_TYPE_" + lower(each) + "=internal\n");
        }
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());
        var catalogs = new health.emrys.afon.config.AfonCatalogs(new FilePropertyProvider(config.toString()));
        assertThat(catalogs.all()).extracting(health.emrys.afon.config.AfonCatalog::id).contains("aws", "azure", "gcp");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void aTreSignsItsMembersInWithTheClientOfItsProjectAndItsSecret(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        ProjectStackFixtures.writeSecrets(home);
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());

        AfonPropertyProvider properties = new FilePropertyProvider(config.toString());
        assertThat(properties.getValue(AfonProperty.OAUTH2_CLIENT_ID)).isEqualTo("xyz-yyyy-nnnn-tre");
        assertThat(properties.getValue(AfonProperty.OAUTH2_CLIENT_SECRET)).isEqualTo("secret-of-xyz-yyyy-nnnn");
        // both are kept with the env's, in secret.properties, which is read after afon.properties: an id in afon.properties would be overridden by the env's
        assertThat(config.resolve("secret.properties")).content().contains("OAUTH2_CLIENT_ID=xyz-yyyy-nnnn-tre", "OAUTH2_CLIENT_SECRET=secret-of-xyz-yyyy-nnnn");
        assertThat(config.resolve("afon.properties")).content().doesNotContain("OAUTH2_CLIENT");
        assertThat(properties.getSecretProperties()).containsKeys("OAUTH2_CLIENT_ID", "OAUTH2_CLIENT_SECRET");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theClientOfTheProjectWinsOverTheOneThatTheEnvKeepsInItsSecrets(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        ProjectStackFixtures.writeSecrets(home);
        Files.writeString(setup.envConfig().resolve("secret.properties"),
                          "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=s3cret\nOAUTH2_CLIENT_ID=env-client\nOAUTH2_CLIENT_SECRET=env-secret\n");
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());

        AfonPropertyProvider properties = new FilePropertyProvider(config.toString());
        assertThat(properties.getValue(AfonProperty.OAUTH2_CLIENT_ID)).isEqualTo("xyz-yyyy-nnnn-tre");
        assertThat(properties.getValue(AfonProperty.OAUTH2_CLIENT_SECRET)).isEqualTo("secret-of-xyz-yyyy-nnnn");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToConfigureATreWithoutTheSecretOfItsClient(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        assertThatThrownBy(() -> ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile()))
            .isInstanceOf(ProjectStackException.class).hasMessageContainingAll("no secret for the TRE client xyz-yyyy-nnnn-tre", "project-secret xyz-yyyy-nnnn tre");
        Path keys = Files.createDirectories(home.resolve("projects/xyz-yyyy-nnnn/secrets"));
        Files.writeString(keys.resolve("tre.secret"), "  \n");
        assertThatThrownBy(() -> ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile()))
            .isInstanceOf(ProjectStackException.class).hasMessageContaining("no secret for the TRE client");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void givesTheProjectsThatAreNotTresNoClientOfTheirOwn(AfonCloud cloud) throws IOException {
        Path file = home.resolve("data.yaml");
        Files.writeString(file, ProjectStackFixtures.dataExample(cloud));
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\nOAUTH2_CLIENT_ID=env-client\n" + ACCOUNTS);
        Files.writeString(env.resolve("secret.properties"), "OAUTH2_CLIENT_SECRET=env-secret\n");
        Files.writeString(env.resolve("catalogs.properties"), "");
        Path config = ProjectStackEnv.configure(new FileProjectStackProvider(file).getProjectStack(), env, home, file);
        AfonPropertyProvider properties = new FilePropertyProvider(config.toString());
        assertThat(properties.getValue(AfonProperty.OAUTH2_CLIENT_ID)).isEqualTo("env-client");
        assertThat(properties.getValue(AfonProperty.OAUTH2_CLIENT_SECRET)).isEqualTo("env-secret");
    }

    @Test
    void findsTheConfigOfAnEnvInTheAfonHomeFromTheHomeOfAProject() throws IOException {
        Path afonHome = Files.createDirectories(home.resolve("afon"));
        Path env = Files.createDirectories(afonHome.resolve("config-dev"));
        Files.createDirectories(afonHome.resolve("projects/xyz-yyyy-nnnn"));
        // the home of a project stack links the entries of the Afon home, but no env
        Path projectHome = Files.createDirectories(home.resolve("project-home"));
        Files.createSymbolicLink(projectHome.resolve("projects"), afonHome.resolve("projects"));

        assertThat(ProjectStackEnv.envConfigDir(projectHome, "dev").toRealPath()).isEqualTo(env.toRealPath());
        // the Afon home itself, as before, and an env that is nowhere is where it would be in the home that is asked
        assertThat(ProjectStackEnv.envConfigDir(afonHome, "dev")).isEqualTo(afonHome.resolve("config-dev"));
        assertThat(ProjectStackEnv.envConfigDir(afonHome, "missing")).isEqualTo(afonHome.resolve("config-missing"));
        assertThat(ProjectStackEnv.envConfigDir(projectHome, "missing")).isEqualTo(projectHome.resolve("config-missing"));
    }

    @Test
    void readsTheFilesOfEveryCloudOfAnEnvWhateverCloudTheRunIsOf() throws IOException {
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n");
        Files.writeString(env.resolve("afon-aws.properties"), "AWS_ACCOUNT=111\n");
        Files.writeString(env.resolve("afon-azure.properties"), "AZURE_SUBSCRIPTION=sub-1\n");
        String previous = System.getProperty("afon.cloud");
        System.setProperty("afon.cloud", "aws");
        try {
            // a TRE project stack on AWS reads the env of a Data Provider on Azure: the account of Azure is the env's, though the run is of AWS
            Properties properties = ProjectStackEnv.envProperties(env);
            assertThat(properties.getProperty("AZURE_SUBSCRIPTION")).isEqualTo("sub-1");
            assertThat(properties.getProperty("AWS_ACCOUNT")).isEqualTo("111");
        } finally {
            if (previous == null) {
                System.clearProperty("afon.cloud");
            } else {
                System.setProperty("afon.cloud", previous);
            }
        }
    }

    @Test
    void replacesTheLineThatAssignsAPropertyWhereItIsAndSaysWhichAreAbsent() {
        java.util.Map<String, String> derived = new java.util.LinkedHashMap<>();
        derived.put("A", "new");
        derived.put("B_C", "");
        derived.put("D", "absent");

        ProjectStackEnv.Replaced replaced = ProjectStackEnv.replaceAssignments("A=1\nX=${A}\nB_C=3\nA=4\n", derived);
        // each in its place, the later line for a property that is given taken out, and the one that no line assigns left to be written
        assertThat(replaced.text()).isEqualTo("A=new\nX=${A}\nB_C=\n");
        assertThat(replaced.absent()).containsOnlyKeys("D");

        // a name that only begins like another, a comment, and a value holding an = are not assignments of it
        assertThat(ProjectStackEnv.replaceAssignments("A_B=1\n# A=2\nX=A=3\nA=4", java.util.Map.of("A", "n")).text()).isEqualTo("A_B=1\n# A=2\nX=A=3\nA=n");
        // the end of the text is kept as it was, and nothing is replaced where nothing is derived
        assertThat(ProjectStackEnv.replaceAssignments("X=1\nA=2", java.util.Map.of("A", "n")).text()).isEqualTo("X=1\nA=n");
        assertThat(ProjectStackEnv.replaceAssignments("", java.util.Map.of("A", "n")).absent()).containsOnlyKeys("A");
        assertThat(ProjectStackEnv.replaceAssignments("A=1\n", java.util.Map.of()).text()).isEqualTo("A=1\n");
    }

    @Test
    void aDerivedPropertyStaysAboveTheLinesThatAreSetInTermsOfIt() throws Exception {
        OnCloud setup = on(AfonCloud.AZURE);
        ProjectStackFixtures.writeSecrets(home);
        Files.writeString(setup.envConfig().resolve("afon-azure.properties"),
                          "AZURE_STORAGE_ACCOUNT=${ENV_NAME}afonstorage\nAZURE_GITEA_BUCKET=${AZURE_STORAGE_ACCOUNT}-gitea\nAZURE_REGION=a-region\nAZURE_KEYCLOAK_EXTERNAL=false\n");
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());

        Path file = config.resolve("afon-azure.properties");
        String text = Files.readString(file);
        // one line for the account, derived, above the line that uses it: a shell reads the file top down, and stops at a variable that is unset
        assertThat(text.lines().filter(line -> line.startsWith("AZURE_STORAGE_ACCOUNT="))).hasSize(1).allMatch(line -> line.startsWith("AZURE_STORAGE_ACCOUNT=afonstorage"));
        assertThat(text.indexOf("AZURE_STORAGE_ACCOUNT=")).isLessThan(text.indexOf("AZURE_GITEA_BUCKET="));
        Process process = new ProcessBuilder("bash", "-c", "set -eu; source " + file + "; echo \"$AZURE_STORAGE_ACCOUNT\"").redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(out).isZero();
        assertThat(out.strip()).startsWith("afonstorage");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void aDerivedPropertyReplacesTheInheritedOneInsteadOfRepeatingIt(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        ProjectStackFixtures.writeSecrets(home);
        Files.writeString(setup.envConfig().resolve("secret.properties"),
                          "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=s3cret\nOAUTH2_CLIENT_ID=env-client\nOAUTH2_CLIENT_SECRET=env-secret\n");
        Path config = ProjectStackEnv.configure(setup.projectStack(), setup.envConfig(), home, setup.projectFile());

        String secrets = Files.readString(config.resolve("secret.properties"));
        assertThat(secrets.lines().filter(line -> line.startsWith("OAUTH2_CLIENT_ID="))).containsExactly("OAUTH2_CLIENT_ID=xyz-yyyy-nnnn-tre");
        assertThat(secrets.lines().filter(line -> line.startsWith("OAUTH2_CLIENT_SECRET="))).containsExactly("OAUTH2_CLIENT_SECRET=secret-of-xyz-yyyy-nnnn");
        // what the env has that is not derived is kept, and the env itself is left as it was
        assertThat(secrets).startsWith("KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=s3cret\n").doesNotContain("env-secret").doesNotContain("env-client");
        assertThat(setup.envConfig().resolve("secret.properties")).content().contains("OAUTH2_CLIENT_SECRET=env-secret");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void generatesOwnPomeriumSecretsForATreAndKeepsThemOnRerun(AfonCloud cloud) throws IOException {
        OnCloud setup = on(cloud);
        Files.writeString(setup.envConfig().resolve("secret.properties"),
                          "KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD=s3cret\nPOMERIUM_SHARED_SECRET=env-shared\nPOMERIUM_COOKIE_SECRET=env-cookie\n");
        ProjectFixtures.writeSecrets(home);
        Path config = ProjectEnv.configure(setup.project(), setup.envConfig(), home, setup.projectFile());

        AfonPropertyProvider first = new FilePropertyProvider(config.toString());
        String shared = first.getValue(AfonProperty.POMERIUM_SHARED_SECRET);
        String cookie = first.getValue(AfonProperty.POMERIUM_COOKIE_SECRET);
        assertThat(shared).isNotEqualTo("env-shared").isNotEqualTo(cookie);
        assertThat(cookie).isNotEqualTo("env-cookie");
        assertThat(java.util.Base64.getDecoder().decode(shared)).hasSize(32);
        assertThat(java.util.Base64.getDecoder().decode(cookie)).hasSize(32);

        ProjectEnv.configure(setup.project(), setup.envConfig(), home, setup.projectFile());
        AfonPropertyProvider second = new FilePropertyProvider(config.toString());
        assertThat(second.getValue(AfonProperty.POMERIUM_SHARED_SECRET)).isEqualTo(shared);
        assertThat(second.getValue(AfonProperty.POMERIUM_COOKIE_SECRET)).isEqualTo(cookie);
    }

    private static boolean cloudOf(String id, AfonCloud cloud) {
        return List.of("cvd", "dbt", lower(cloud)).contains(id);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheCloudOfTheIdentityProjectOnlyWhenItIsNotTheProjects(AfonCloud cloud) throws IOException {
        AfonCloud other = AfonCloud.values()[(cloud.ordinal() + 1) % AfonCloud.values().length];
        Path env = Files.createDirectories(home.resolve("config-dev"));
        Files.writeString(env.resolve("afon.properties"), "ENV_NAME=dev\n" + ACCOUNTS);
        Path file = home.resolve("data.yaml");
        for (AfonCloud identityCloud : List.of(cloud, other)) {
            Path idp = home.resolve("projects/idp/project.yaml");
            Files.createDirectories(idp.getParent());
            Files.writeString(idp, ProjectStackFixtures.identityExample(identityCloud));
            Files.writeString(file, ProjectStackFixtures.dataExample(cloud).replace("identity: {", "identity: {projectStack: idp, "));
            ProjectStackFixtures.writeSecrets(home);
            Path config = ProjectStackEnv.configure(new FileProjectStackProvider(file).getProjectStack(), env, home, file);
            AfonPropertyProvider properties = new FilePropertyProvider(config.toString());
            // the kubeconfig is that of the identity's stack, wherever it is, and its cloud is named only if it differs
            assertThat(properties.getValue(AfonProperty.KEYCLOAK_KUBECONFIG)).endsWith("cdktf-idp/kube/" + lower(identityCloud) + "-keycloak.config");
            assertThat(properties.getValue(AfonProperty.KEYCLOAK_CLOUD, "")).isEqualTo(identityCloud == cloud ? "" : lower(identityCloud));
        }
    }
}
