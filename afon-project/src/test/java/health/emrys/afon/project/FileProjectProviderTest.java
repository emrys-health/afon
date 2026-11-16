package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.project.ProjectFixtures.lower;
import static health.emrys.afon.project.ProjectFixtures.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every test is run on every cloud, see {@link ProjectFixtures}.
 */
class FileProjectProviderTest {

    private static final String VALID = """
        project: {id: xyz-yyyy-nnnn, cloud: ${cloud}}
        identity: {issuerUrl: "https://id.example.org/realms/polaris", acr: mfa}
        providers:
          - {id: data1, catalogUri: "https://p.example/api", catalogName: w, audience: provider-data1, bucket: b}
        stacks:
          create: {auditBucket: audit, gitBucket: gitea}
          git: {domain: git.example.org}
          tre: {domains: {}}
        """;

    private static String valid(AfonCloud cloud) {
        return render(VALID, cloud);
    }

    private static ProjectException invalid(String yaml) {
        try {
            FileProjectProvider.parse(yaml);
        } catch (ProjectException e) {
            return e;
        }
        throw new AssertionError("expected the project to be invalid");
    }

    private static AfonCloud anotherThan(AfonCloud cloud) {
        return AfonCloud.values()[(cloud.ordinal() + 1) % AfonCloud.values().length];
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void loadsTheExampleFile(AfonCloud cloud) {
        TreProject project = ProjectFixtures.tre(ProjectFixtures.example(cloud));
        assertThat(project.id().value()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(project.cloud()).isEqualTo(cloud);
        assertThat(project.identity().treClient()).isEqualTo("xyz-yyyy-nnnn-tre");
        assertThat(project.identity().gitClient()).isEqualTo("xyz-yyyy-nnnn-git");
        assertThat(project.providers()).extracting(TreProject.Provider::cloud).containsExactly(cloud, cloud);
        assertThat(project.tre().stack().toString()).isEqualTo(lower(cloud) + "-full");
        assertThat(project.tre().domains()).containsEntry(cloud.name() + "_JUPYTER_DOMAIN_NAME", "jupyter.xyz-yyyy-nnnn.example.org");
        assertThat(project.tre().teardown()).isEqualTo("22:00");
        assertThat(project.tre().timeZone()).isEqualTo("Europe/London");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheCloudOfAProvidersStorageFromItselfNotFromTheProject(AfonCloud cloud) {
        AfonCloud other = anotherThan(cloud);
        TreProject project = ProjectFixtures.tre(valid(cloud).replace("bucket: b}", "bucket: b, cloud: " + lower(other) + "}"));
        assertThat(project.providers()).extracting(TreProject.Provider::cloud).containsExactly(other);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void defaultsTheTreStackToTheFullOneOfTheProjectsCloud(AfonCloud cloud) {
        TreProject project = ProjectFixtures.tre(valid(cloud));
        assertThat(project.cloud()).isEqualTo(cloud);
        assertThat(project.tre().stack().toString()).isEqualTo(lower(cloud) + "-full");
        assertThat(project.tre().teardown()).isNull();
        assertThat(project.identity().treClient()).isEqualTo("xyz-yyyy-nnnn-tre");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesADomainOfTheProjectsCloudAndRejectsOneThatIsNotAKnownProperty(AfonCloud cloud) {
        String prefix = cloud.name() + "_";
        TreProject project = ProjectFixtures.tre(valid(cloud).replace("tre: {domains: {}}",
                                                                               "tre: {domains: {" + prefix + "JUPYTER_DOMAIN_NAME: j.example.org}}"));
        assertThat(project.tre().domains()).containsEntry(prefix + "JUPYTER_DOMAIN_NAME", "j.example.org");
        assertThat(invalid(valid(cloud).replace("tre: {domains: {}}", "tre: {domains: {" + prefix + "JUPYTR_DOMAIN_NAME: j}}")).problems())
            .contains("stacks.tre.domains." + prefix + "JUPYTR_DOMAIN_NAME is not a known property");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void reportsEveryProblemAtOnce(AfonCloud cloud) {
        ProjectException e = invalid(valid(cloud).replace("xyz-yyyy-nnnn", "CVD_1").replace("audience: provider-data1, ", ""));
        assertThat(e.problems()).anyMatch(p -> p.startsWith("project.id")).anyMatch(p -> p.equals("providers[0].audience is required"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnUnknownKey(AfonCloud cloud) {
        assertThat(invalid(valid(cloud).replace("git: {domain: git.example.org}", "git: {domian: git.example.org}")).problems())
            .anyMatch(p -> p.startsWith("stacks.git.domian is not a known key"))
            .anyMatch(p -> p.equals("stacks.git.domain is required"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsDuplicateProviderIds(AfonCloud cloud) {
        String twice = valid(cloud).replace("bucket: b}", "bucket: b}\n  - {id: data1, catalogUri: \"https://q\", catalogName: w, audience: a, bucket: c}");
        assertThat(invalid(twice).problems()).contains("providers[1].id 'data1' is used by another provider");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void hasNoMembersToManageIfTheFileListsNone(AfonCloud cloud) {
        assertThat(((TreProject) FileProjectProvider.parse(valid(cloud))).members()).isNull();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void readsMembersAsEmailsOrMappings(AfonCloud cloud) {
        String yaml = valid(cloud) + "members:\n  - Ada@Example.org\n  - {email: grace@example.org, firstName: Grace, lastName: Hopper}\n";
        assertThat(((TreProject) FileProjectProvider.parse(yaml)).members()).containsExactly(
            new TreProject.Member("ada@example.org", null, null), new TreProject.Member("grace@example.org", "Grace", "Hopper"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void readsAnEmptyListOfMembersAsNoMembers(AfonCloud cloud) {
        assertThat(((TreProject) FileProjectProvider.parse(valid(cloud) + "members: []\n")).members()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsMembersThatAreNotEmailsOrAreListedTwice(AfonCloud cloud) {
        String yaml = valid(cloud) + "members: [ada@example.org, nobody, ADA@example.org]\n";
        assertThat(invalid(yaml).problems()).contains("members[1].email 'nobody' is not an email", "members[2].email 'ada@example.org' is listed twice");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsMembersThatAreNotAList(AfonCloud cloud) {
        assertThat(invalid(valid(cloud) + "members: ada@example.org\n").problems()).contains("members must be a list, which may be empty");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsATreStackOfAnotherCloud(AfonCloud cloud) {
        String stack = lower(anotherThan(cloud)) + "-full";
        assertThat(invalid(valid(cloud).replace("tre: {domains: {}}", "tre: {stack: " + stack + "}")).problems())
            .anyMatch(p -> p.contains("is not on the project's cloud"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnInsecureIssuer(AfonCloud cloud) {
        assertThat(invalid(valid(cloud).replace("https://id", "http://id")).problems()).anyMatch(p -> p.contains("https URL"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void findsAProjectByItsIdInItsOwnDir(AfonCloud cloud, @TempDir Path home) throws Exception {
        ProjectFixtures.writeExample(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"), cloud);
        FileProjectProvider provider = FileProjectProvider.forProject(home, "xyz-yyyy-nnnn");
        assertThat(provider.file()).isEqualTo(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"));
        assertThat(provider.exists()).isTrue();
        assertThat(provider.getProject().id().value()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(FileProjectProvider.keysDir(home, "xyz-yyyy-nnnn")).isEqualTo(home.resolve("projects/xyz-yyyy-nnnn/keys"));
        assertThat(FileProjectProvider.forProject(home, "other-project").exists()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnIdThatWouldNamePathsOutsideTheProjectsDir(AfonCloud cloud, @TempDir Path home) {
        assertThatThrownBy(() -> FileProjectProvider.forProject(home, "../config")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileProjectProvider.projectFile(home, "a/b")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAFileWhoseIdIsNotThatOfItsDir(AfonCloud cloud, @TempDir Path home) throws Exception {
        ProjectFixtures.writeExample(home.resolve("projects/other-project/project.yaml"), cloud);
        assertThatThrownBy(() -> FileProjectProvider.forProject(home, "other-project").getProject())
            .isInstanceOf(ProjectException.class).hasMessageContaining("is not 'other-project'");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void hasNoGitOfItsOwnWithoutStacksGit(AfonCloud cloud) {
        TreProject project = ProjectFixtures.tre(valid(cloud).replace("create: {auditBucket: audit, gitBucket: gitea}", "create: {auditBucket: audit}")
                                                                    .replace("  git: {domain: git.example.org}\n", ""));
        assertThat(project.git()).isNull();
        assertThat(project.identity().gitClient()).isNull();
        assertThat(project.create().gitBucket()).isNull();
        assertThat(ProjectPlan.of(project).stages()).extracting(ProjectPlan.Stage::id)
            .containsExactly(ProjectPlan.StageId.CREATE, ProjectPlan.StageId.REGISTER, ProjectPlan.StageId.TRE);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAGitClientForAProjectWithNoGitOfItsOwn(AfonCloud cloud) {
        String noGit = valid(cloud).replace("  git: {domain: git.example.org}\n", "")
                                   .replace("identity: {issuerUrl:", "identity: {clients: {git: other-git}, issuerUrl:");
        assertThat(invalid(noGit).problems()).anyMatch(p -> p.startsWith("identity.clients.git is set but there is no stacks.git"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheEnvWhoseConfigItIsMadeFrom(AfonCloud cloud) {
        assertThat(FileProjectProvider.parse(valid(cloud)).env()).isNull();
        assertThat(FileProjectProvider.parse(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", env: emrys2}")).env())
            .isEqualTo("emrys2");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnEnvThatIsNotANameAsItWouldNameAPath(AfonCloud cloud) {
        assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", env: ../secret}")).problems())
            .contains("project.env '../secret' is not the name of an env, eg dev for config-dev");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesWhereItDeploysFromItsEnvNotFromItsOwnFile(AfonCloud cloud) {
        // the region and the account are those of the config it is made from, so naming them in the file is a mistake, not a second source
        assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", region: a-region, account: acct}")).problems())
            .anyMatch(p -> p.startsWith("project.region is not a known key"))
            .anyMatch(p -> p.startsWith("project.account is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheAddressItsClusterLeavesFrom(AfonCloud cloud) {
        assertThat(ProjectFixtures.tre(valid(cloud)).egressIp()).isNull();
        assertThat(ProjectFixtures.tre(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", egressIp: 20.50.115.68}")).egressIp())
            .isEqualTo("20.50.115.68");
        assertThat(ProjectFixtures.tre(ProjectFixtures.example(cloud)).egressIp()).isEqualTo("203.0.113.10");
    }

    /** The nodes of a cluster, as the cloud's node pool property takes them, or null if it has none, as AWS has not. */
    private static String pool(AfonCloud cloud) {
        return switch (cloud) {
            case AZURE -> "vmSize: Standard_D2s_v3, nodeCount: 2";
            case GCP -> "machineType: e2-standard-4, nodeCount: 2";
            case AWS -> null;
        };
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheNodesOfItsClusterWithTheKeysOfItsCloud(AfonCloud cloud) {
        assertThat(ProjectFixtures.tre(valid(cloud)).nodePool()).isEmpty();
        String withPool = valid(cloud) + "cluster: {nodePool: {" + pool(cloud) + "}}\n";
        if (pool(cloud) == null) {
            assertThat(invalid(withPool).problems()).anyMatch(p -> p.startsWith("cluster.nodePool is not available on aws"));
            return;
        }
        assertThat(ProjectFixtures.tre(withPool).nodePool()).hasSize(2).containsEntry("nodeCount", "2");
        // an identity project has its own, as it is a cluster of its own
        assertThat(ProjectFixtures.identity(IDENTITY_PROJECT.replace("${cloud}", lower(cloud)) + "cluster: {nodePool: {" + pool(cloud) + "}}\n").nodePool())
            .hasSize(2).containsEntry("nodeCount", "2");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsANodePoolOfAnotherCloudOrWithABadValue(AfonCloud cloud) {
        Assumptions.assumeTrue(pool(cloud) != null);
        String other = pool(anotherThan(cloud) == AfonCloud.AWS ? cloud : anotherThan(cloud));
        String known = cloud == AfonCloud.AZURE ? "vmSize: Standard_D2s_v3" : "machineType: e2-standard-4";
        assertThat(invalid(valid(cloud) + "cluster: {nodePool: {nodeSize: big}}\n").problems())
            .anyMatch(p -> p.startsWith("cluster.nodePool.nodeSize is not a known key on " + lower(cloud)));
        for (String bad : new String[]{"0", "two", "-1", "2.5", "1,2"}) {
            assertThat(invalid(valid(cloud) + "cluster: {nodePool: {" + known + ", nodeCount: \"" + bad + "\"}}\n").problems())
                .as(bad).anyMatch(p -> p.contains("cluster.nodePool.nodeCount"));
        }
        assertThat(invalid(valid(cloud) + "cluster: {nodePool: {" + known.replaceAll(":.*", ": \"a;b\"") + "}}\n").problems())
            .anyMatch(p -> p.contains("has a separator or a space"));
        assertThat(other).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void delegatesTheDnsZonesByHandUnlessTheProjectSaysRoute53(AfonCloud cloud) {
        assertThat(ProjectFixtures.tre(valid(cloud)).dnsDelegation()).isEqualTo(DnsDelegation.MANUAL);
        assertThat(ProjectFixtures.tre(valid(cloud) + "dns: {delegation: route53}\n").dnsDelegation()).isEqualTo(DnsDelegation.ROUTE53);
        assertThat(ProjectFixtures.identity(IDENTITY_PROJECT.replace("${cloud}", lower(cloud)) + "dns: {delegation: route53}\n").dnsDelegation())
            .isEqualTo(DnsDelegation.ROUTE53);
        assertThat(invalid(valid(cloud) + "dns: {delegation: carrier-pigeon}\n").problems())
            .anyMatch(p -> p.startsWith("dns.delegation 'carrier-pigeon' is not a way to delegate the DNS zones: one of [manual, route53]"));
        assertThat(invalid(valid(cloud) + "dns: {zone: x}\n").problems()).anyMatch(p -> p.startsWith("dns.zone is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnEgressIpThatIsNotAnIpv4Address(AfonCloud cloud) {
        for (String bad : new String[]{"afon-egress-ip", "20.50.115", "20.50.115.256", "20.50.115.68/32", "$(id)"}) {
            assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", egressIp: \"" + bad + "\"}")).problems())
                .as(bad).anyMatch(p -> p.startsWith("project.egressIp '" + bad + "' is not an IPv4 address"));
        }
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheIdentityProjectItUses(AfonCloud cloud) {
        assertThat(ProjectFixtures.tre(valid(cloud)).identity().project()).isNull();
        assertThat(ProjectFixtures.tre(valid(cloud).replace("identity: {", "identity: {project: idp, ")).identity().project())
            .isEqualTo("idp");
        assertThat(invalid(valid(cloud).replace("identity: {", "identity: {project: Bad_Id, ")).problems())
            .anyMatch(p -> p.startsWith("identity.project: Project id 'Bad_Id' is not a lower-case DNS label"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsInvalidYaml(AfonCloud cloud) {
        assertThatThrownBy(() -> FileProjectProvider.parse("project: [")).isInstanceOf(ProjectException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void plansTheStacksInOrderWithStatePerProject(AfonCloud cloud) {
        String c = lower(cloud);
        ProjectPlan plan = ProjectPlan.of(FileProjectProvider.parse(ProjectFixtures.example(cloud)));
        assertThat(plan.stages()).extracting(ProjectPlan.Stage::id).containsExactly(
            ProjectPlan.StageId.CREATE, ProjectPlan.StageId.REGISTER, ProjectPlan.StageId.GIT, ProjectPlan.StageId.TRE);
        assertThat(plan.stages()).extracting(ProjectPlan.Stage::stateKey).containsExactly(
            "xyz-yyyy-nnnn/" + c + "-create", null, "xyz-yyyy-nnnn/" + c + "-git", "xyz-yyyy-nnnn/" + c + "-full");
        assertThat(plan.nightlyStages()).extracting(ProjectPlan.Stage::id).containsExactly(ProjectPlan.StageId.TRE);
    }

    // the identity project

    private static final String IDENTITY_PROJECT = """
        project: {id: idp, cloud: ${cloud}, role: identity}
        """;

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void loadsTheIdentityProject(AfonCloud cloud) {
        IdentityProject project = ProjectFixtures.identity(ProjectFixtures.identityExample(cloud));
        assertThat(project.role()).isEqualTo(ProjectRole.IDENTITY);
        assertThat(project.cloud()).isEqualTo(cloud);
        assertThat(project.egressIp()).isEqualTo("203.0.113.20");
        assertThat(project.stack().toString()).isEqualTo(lower(cloud) + "-keycloak");
        assertThat(project.domain()).isEqualTo("oidc.example.org");
        assertThat(project.role()).isEqualTo(ProjectRole.IDENTITY);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void needsNothingMoreThanItsRoleToBeAnIdentityProject(AfonCloud cloud) {
        IdentityProject project = ProjectFixtures.identity(render(IDENTITY_PROJECT, cloud));
        assertThat(project.domain()).isNull();
        assertThat(project.stack().toString()).isEqualTo(lower(cloud) + "-keycloak");
        assertThat(project.egressIp()).isNull();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheStackOfTheIdentityFromTheFileIfItNamesOneOfItsCloud(AfonCloud cloud) {
        IdentityProject project = ProjectFixtures.identity(render(IDENTITY_PROJECT, cloud) + "stacks: {identity: {stack: " + lower(cloud) + "-sso}}\n");
        assertThat(project.stack().toString()).isEqualTo(lower(cloud) + "-sso");
        assertThat(invalid(render(IDENTITY_PROJECT, cloud) + "stacks: {identity: {stack: " + lower(anotherThan(cloud)) + "-keycloak}}\n").problems())
            .anyMatch(p -> p.contains("is not on the project's cloud"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsWhatBelongsToATreProjectInAnIdentityProject(AfonCloud cloud) {
        String yaml = valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", role: identity}");
        assertThat(invalid(yaml).problems())
            .anyMatch(p -> p.startsWith("identity is not a known key"))
            .anyMatch(p -> p.startsWith("providers is not a known key"))
            .anyMatch(p -> p.startsWith("stacks.create is not a known key"))
            .anyMatch(p -> p.startsWith("stacks.git is not a known key"))
            .anyMatch(p -> p.startsWith("stacks.tre is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsTheIdentityStackInATreProject(AfonCloud cloud) {
        assertThat(invalid(valid(cloud) + "  identity: {domain: oidc.example.org}\n").problems())
            .anyMatch(p -> p.startsWith("stacks.identity is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void isATreProjectUnlessItSaysItIsAnIdentityProject(AfonCloud cloud) {
        assertThat(FileProjectProvider.parse(valid(cloud)).role()).isEqualTo(ProjectRole.TRE);
        assertThat(FileProjectProvider.parse(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", role: tre}")).role())
            .isEqualTo(ProjectRole.TRE);
        assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", role: catalog}")).problems())
            .contains("project.role 'catalog' is not a role of a project: one of [identity, tre]");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void plansTheIdentityProjectAsItsStackAloneNeverRebuiltAtNight(AfonCloud cloud) {
        String c = lower(cloud);
        ProjectPlan plan = ProjectPlan.of(FileProjectProvider.parse(ProjectFixtures.identityExample(cloud)));
        assertThat(plan.stages()).extracting(ProjectPlan.Stage::id).containsExactly(ProjectPlan.StageId.CREATE, ProjectPlan.StageId.IDENTITY);
        assertThat(plan.stages()).extracting(ProjectPlan.Stage::stateKey).containsExactly("idp/" + c + "-create", "idp/" + c + "-keycloak");
        assertThat(plan.nightlyStages()).isEmpty();
    }
}
