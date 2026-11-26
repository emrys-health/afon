package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.project.ProjectStackFixtures.lower;
import static health.emrys.afon.project.ProjectStackFixtures.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every test is run on every cloud, see {@link ProjectStackFixtures}.
 */
class FileProjectStackProviderTest {

    private static final String VALID = """
        projectStack: {id: xyz-yyyy-nnnn, cloud: ${cloud}}
        identity: {issuerUrl: "https://id.example.org/realms/polaris", acr: mfa}
        dataProviders:
          defined:
            - {id: data1, catalogUri: "https://p.example/api", catalogName: w, audience: provider-data1, bucket: b}
        members: [ada@example.org]
        stacks:
          create: {auditBucket: audit, gitBucket: gitea}
          git: {domain: git.example.org}
          tre: {domains: {}}
        """;

    private static String valid(AfonCloud cloud) {
        return render(VALID, cloud);
    }

    private static ProjectStackException invalid(String yaml) {
        try {
            FileProjectStackProvider.parse(yaml);
        } catch (ProjectStackException e) {
            return e;
        }
        throw new AssertionError("expected the project stack to be invalid");
    }

    private static AfonCloud anotherThan(AfonCloud cloud) {
        return AfonCloud.values()[(cloud.ordinal() + 1) % AfonCloud.values().length];
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void loadsTheExampleFile(AfonCloud cloud) {
        TreProjectStack projectStack = ProjectStackFixtures.tre(ProjectStackFixtures.example(cloud));
        assertThat(projectStack.id().value()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(projectStack.cloud()).isEqualTo(cloud);
        assertThat(projectStack.identity().treClient()).isEqualTo("xyz-yyyy-nnnn-tre");
        assertThat(projectStack.identity().gitClient()).isEqualTo("xyz-yyyy-nnnn-git");
        assertThat(projectStack.dataProviders()).extracting(TreProjectStack.DataProvider::cloud).containsExactly(cloud, cloud);
        assertThat(projectStack.tre().stack().toString()).isEqualTo(lower(cloud) + "-full");
        assertThat(projectStack.tre().domains()).containsEntry(cloud.name() + "_JUPYTER_DOMAIN_NAME", "jupyter.xyz-yyyy-nnnn.example.org");
        assertThat(projectStack.tre().teardown()).isEqualTo("22:00");
        assertThat(projectStack.tre().timeZone()).isEqualTo("Europe/London");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheCloudOfAProvidersStorageFromItselfNotFromTheProject(AfonCloud cloud) {
        AfonCloud other = anotherThan(cloud);
        TreProjectStack projectStack = ProjectStackFixtures.tre(valid(cloud).replace("bucket: b}", "bucket: b, cloud: " + lower(other) + "}"));
        assertThat(projectStack.dataProviders()).extracting(TreProjectStack.DataProvider::cloud).containsExactly(other);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void defaultsTheTreStackToTheFullOneOfTheProjectsCloud(AfonCloud cloud) {
        TreProjectStack projectStack = ProjectStackFixtures.tre(valid(cloud));
        assertThat(projectStack.cloud()).isEqualTo(cloud);
        assertThat(projectStack.tre().stack().toString()).isEqualTo(lower(cloud) + "-full");
        assertThat(projectStack.tre().teardown()).isNull();
        assertThat(projectStack.identity().treClient()).isEqualTo("xyz-yyyy-nnnn-tre");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesADomainOfTheProjectsCloudAndRejectsOneThatIsNotAKnownProperty(AfonCloud cloud) {
        String prefix = cloud.name() + "_";
        TreProjectStack projectStack = ProjectStackFixtures.tre(valid(cloud).replace("tre: {domains: {}}",
                                                                               "tre: {domains: {" + prefix + "JUPYTER_DOMAIN_NAME: j.example.org}}"));
        assertThat(projectStack.tre().domains()).containsEntry(prefix + "JUPYTER_DOMAIN_NAME", "j.example.org");
        assertThat(invalid(valid(cloud).replace("tre: {domains: {}}", "tre: {domains: {" + prefix + "JUPYTR_DOMAIN_NAME: j}}")).problems())
            .contains("stacks.tre.domains." + prefix + "JUPYTR_DOMAIN_NAME is not a known property");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void reportsEveryProblemAtOnce(AfonCloud cloud) {
        ProjectStackException e = invalid(valid(cloud).replace("xyz-yyyy-nnnn", "CVD_1").replace("audience: provider-data1, ", ""));
        assertThat(e.problems()).anyMatch(p -> p.startsWith("projectStack.id")).anyMatch(p -> p.equals("dataProviders.defined[0].audience is required"));
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
        String twice = valid(cloud).replace("bucket: b}", "bucket: b}\n    - {id: data1, catalogUri: \"https://q\", catalogName: w, audience: a, bucket: c}");
        assertThat(invalid(twice).problems()).contains("dataProviders.defined[1].id 'data1' is used by another Data Provider");
    }

    private static String withMembers(AfonCloud cloud, String members) {
        return valid(cloud).replace("members: [ada@example.org]\n", members);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void listsTheSmokeMembersApartFromTheMembersAndSignsTheSmokeTestsInAsTheFirst(AfonCloud cloud) {
        TreProjectStack none = (TreProjectStack) FileProjectStackProvider.parse(valid(cloud));
        assertThat(none.smokeMembers()).isEmpty();
        assertThat(none.smokeTestMember()).isEmpty();

        TreProjectStack listed = (TreProjectStack) FileProjectStackProvider.parse(
            valid(cloud) + "smokeMembers:\n  - Smoke@example.org\n  - {email: smoke2@example.org, firstName: Smoke, lastName: Two}\n");
        assertThat(listed.smokeMembers()).extracting(TreProjectStack.Member::email).containsExactly("smoke@example.org", "smoke2@example.org");
        assertThat(listed.smokeTestMember().orElseThrow().email()).isEqualTo("smoke@example.org");
        assertThat(listed.members()).extracting(TreProjectStack.Member::email).containsExactly("ada@example.org");
        assertThat(listed.allMembers()).extracting(TreProjectStack.Member::email).containsExactly("ada@example.org", "smoke@example.org", "smoke2@example.org");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesASmokeMemberThatIsAlsoAMemberOrListedTwiceOrNotAnEmailOrNotAList(AfonCloud cloud) {
        assertThat(invalid(valid(cloud) + "smokeMembers:\n  - ada@example.org\n").problems())
            .anyMatch(p -> p.startsWith("smokeMembers[0].email 'ada@example.org' is also a member"));
        assertThat(invalid(valid(cloud) + "smokeMembers:\n  - s@example.org\n  - S@example.org\n").problems())
            .anyMatch(p -> p.startsWith("smokeMembers[1].email 's@example.org' is listed twice"));
        assertThat(invalid(valid(cloud) + "smokeMembers:\n  - nobody\n").problems()).anyMatch(p -> p.startsWith("smokeMembers[0].email 'nobody' is not an email"));
        assertThat(invalid(valid(cloud) + "smokeMembers: s@example.org\n").problems()).anyMatch(p -> p.startsWith("smokeMembers must be a list"));
        assertThat(invalid(valid(cloud) + "smokeMembers:\n  - {email: s@example.org, smokeTests: true}\n").problems())
            .anyMatch(p -> p.contains("smokeMembers[0].smokeTests is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void readsMembersAsEmailsOrMappings(AfonCloud cloud) {
        String yaml = withMembers(cloud, "members:\n  - Ada@Example.org\n  - {email: grace@example.org, firstName: Grace, lastName: Hopper}\n");
        assertThat(((TreProjectStack) FileProjectStackProvider.parse(yaml)).members()).containsExactly(
            new TreProjectStack.Member("ada@example.org", null, null), new TreProjectStack.Member("grace@example.org", "Grace", "Hopper"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void requiresAtLeastOneMember(AfonCloud cloud) {
        for (String none : new String[]{"", "members:\n", "members: []\n", "members: ~\n"}) {
            assertThat(invalid(withMembers(cloud, none)).problems()).contains("members is required: a project stack has at least one member, by email");
        }
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsMembersThatAreNotEmailsOrAreListedTwice(AfonCloud cloud) {
        String yaml = withMembers(cloud, "members: [ada@example.org, nobody, ADA@example.org]\n");
        assertThat(invalid(yaml).problems()).contains("members[1].email 'nobody' is not an email", "members[2].email 'ada@example.org' is listed twice");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsMembersThatAreNotAList(AfonCloud cloud) {
        assertThat(invalid(withMembers(cloud, "members: ada@example.org\n")).problems()).contains("members must be a list of at least one member");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsATreStackOfAnotherCloud(AfonCloud cloud) {
        String stack = lower(anotherThan(cloud)) + "-full";
        assertThat(invalid(valid(cloud).replace("tre: {domains: {}}", "tre: {stack: " + stack + "}")).problems())
            .anyMatch(p -> p.contains("is not on the project stack's cloud"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnInsecureIssuer(AfonCloud cloud) {
        assertThat(invalid(valid(cloud).replace("https://id", "http://id")).problems()).anyMatch(p -> p.contains("https URL"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void findsAProjectByItsIdInItsOwnDir(AfonCloud cloud, @TempDir Path home) throws Exception {
        ProjectStackFixtures.writeExample(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"), cloud);
        FileProjectStackProvider provider = FileProjectStackProvider.forProjectStack(home, "xyz-yyyy-nnnn");
        assertThat(provider.file()).isEqualTo(home.resolve("projects/xyz-yyyy-nnnn/project.yaml"));
        assertThat(provider.exists()).isTrue();
        assertThat(provider.getProjectStack().id().value()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(FileProjectStackProvider.secretsDir(home, "xyz-yyyy-nnnn")).isEqualTo(home.resolve("projects/xyz-yyyy-nnnn/secrets"));
        assertThat(FileProjectStackProvider.forProjectStack(home, "other-project").exists()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAnIdThatWouldNamePathsOutsideTheProjectsDir(AfonCloud cloud, @TempDir Path home) {
        assertThatThrownBy(() -> FileProjectStackProvider.forProjectStack(home, "../config")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileProjectStackProvider.projectFile(home, "a/b")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAFileWhoseIdIsNotThatOfItsDir(AfonCloud cloud, @TempDir Path home) throws Exception {
        ProjectStackFixtures.writeExample(home.resolve("projects/other-project/project.yaml"), cloud);
        assertThatThrownBy(() -> FileProjectStackProvider.forProjectStack(home, "other-project").getProjectStack())
            .isInstanceOf(ProjectStackException.class).hasMessageContaining("is not 'other-project'");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void hasNoGitOfItsOwnWithoutStacksGit(AfonCloud cloud) {
        TreProjectStack projectStack = ProjectStackFixtures.tre(valid(cloud).replace("create: {auditBucket: audit, gitBucket: gitea}", "create: {auditBucket: audit}")
                                                                    .replace("  git: {domain: git.example.org}\n", ""));
        assertThat(projectStack.git()).isNull();
        assertThat(projectStack.identity().gitClient()).isNull();
        assertThat(projectStack.create().gitBucket()).isNull();
        assertThat(ProjectStackPlan.of(projectStack).stages()).extracting(ProjectStackPlan.Stage::id)
            .containsExactly(ProjectStackPlan.StageId.CREATE, ProjectStackPlan.StageId.REGISTER, ProjectStackPlan.StageId.TRE);
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
        assertThat(FileProjectStackProvider.parse(valid(cloud)).env()).isNull();
        assertThat(FileProjectStackProvider.parse(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", env: emrys2}")).env())
            .isEqualTo("emrys2");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsAnEnvThatIsNotANameAsItWouldNameAPath(AfonCloud cloud) {
        assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", env: ../secret}")).problems())
            .contains("projectStack.env '../secret' is not the name of an env, eg dev for config-dev");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesWhereItDeploysFromItsEnvNotFromItsOwnFile(AfonCloud cloud) {
        // the region and the account are those of the config it is made from, so naming them in the file is a mistake, not a second source
        assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", region: a-region, account: acct}")).problems())
            .anyMatch(p -> p.startsWith("projectStack.region is not a known key"))
            .anyMatch(p -> p.startsWith("projectStack.account is not a known key"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheAddressItsClusterLeavesFrom(AfonCloud cloud) {
        assertThat(ProjectStackFixtures.tre(valid(cloud)).egressIp()).isNull();
        assertThat(ProjectStackFixtures.tre(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", egressIp: 20.50.115.68}")).egressIp())
            .isEqualTo("20.50.115.68");
        assertThat(ProjectStackFixtures.tre(ProjectStackFixtures.example(cloud)).egressIp()).isEqualTo("203.0.113.10");
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
        assertThat(ProjectStackFixtures.tre(valid(cloud)).nodePool()).isEmpty();
        String withPool = valid(cloud) + "cluster: {nodePool: {" + pool(cloud) + "}}\n";
        if (pool(cloud) == null) {
            assertThat(invalid(withPool).problems()).anyMatch(p -> p.startsWith("cluster.nodePool is not available on aws"));
            return;
        }
        assertThat(ProjectStackFixtures.tre(withPool).nodePool()).hasSize(2).containsEntry("nodeCount", "2");
        // an identity project stack has its own, as it is a cluster of its own
        assertThat(ProjectStackFixtures.identity(IDENTITY_PROJECT.replace("${cloud}", lower(cloud)) + "cluster: {nodePool: {" + pool(cloud) + "}}\n").nodePool())
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
        assertThat(ProjectStackFixtures.tre(valid(cloud)).dnsDelegation()).isEqualTo(DnsDelegation.MANUAL);
        assertThat(ProjectStackFixtures.tre(valid(cloud) + "dns: {delegation: route53}\n").dnsDelegation()).isEqualTo(DnsDelegation.ROUTE53);
        assertThat(ProjectStackFixtures.identity(IDENTITY_PROJECT.replace("${cloud}", lower(cloud)) + "dns: {delegation: route53}\n").dnsDelegation())
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
                .as(bad).anyMatch(p -> p.startsWith("projectStack.egressIp '" + bad + "' is not an IPv4 address"));
        }
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void namesTheIdentityProjectItUses(AfonCloud cloud) {
        assertThat(ProjectStackFixtures.tre(valid(cloud)).identity().projectStack()).isNull();
        assertThat(ProjectStackFixtures.tre(valid(cloud).replace("identity: {", "identity: {projectStack: idp, ")).identity().projectStack())
            .isEqualTo("idp");
        assertThat(invalid(valid(cloud).replace("identity: {", "identity: {projectStack: Bad_Id, ")).problems())
            .anyMatch(p -> p.startsWith("identity.projectStack: Project stack id 'Bad_Id' is not a lower-case DNS label"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsInvalidYaml(AfonCloud cloud) {
        assertThatThrownBy(() -> FileProjectStackProvider.parse("projectStack: [")).isInstanceOf(ProjectStackException.class);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void plansTheStacksInOrderWithStatePerProject(AfonCloud cloud) {
        String c = lower(cloud);
        ProjectStackPlan plan = ProjectStackPlan.of(FileProjectStackProvider.parse(ProjectStackFixtures.example(cloud)));
        assertThat(plan.stages()).extracting(ProjectStackPlan.Stage::id).containsExactly(
            ProjectStackPlan.StageId.CREATE, ProjectStackPlan.StageId.REGISTER, ProjectStackPlan.StageId.GIT, ProjectStackPlan.StageId.TRE);
        assertThat(plan.stages()).extracting(ProjectStackPlan.Stage::stateKey).containsExactly(
            "xyz-yyyy-nnnn/" + c + "-create", null, "xyz-yyyy-nnnn/" + c + "-git", "xyz-yyyy-nnnn/" + c + "-full");
        assertThat(plan.nightlyStages()).extracting(ProjectStackPlan.Stage::id).containsExactly(ProjectStackPlan.StageId.TRE);
    }

    // the identity project stack

    private static final String IDENTITY_PROJECT = """
        projectStack: {id: idp, cloud: ${cloud}, role: identity}
        """;

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void loadsTheIdentityProject(AfonCloud cloud) {
        IdentityProjectStack projectStack = ProjectStackFixtures.identity(ProjectStackFixtures.identityExample(cloud));
        assertThat(projectStack.role()).isEqualTo(ProjectStackRole.IDENTITY);
        assertThat(projectStack.cloud()).isEqualTo(cloud);
        assertThat(projectStack.egressIp()).isEqualTo("203.0.113.20");
        assertThat(projectStack.stack().toString()).isEqualTo(lower(cloud) + "-keycloak");
        assertThat(projectStack.domain()).isEqualTo("oidc.example.org");
        assertThat(projectStack.role()).isEqualTo(ProjectStackRole.IDENTITY);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void needsNothingMoreThanItsRoleToBeAnIdentityProject(AfonCloud cloud) {
        IdentityProjectStack projectStack = ProjectStackFixtures.identity(render(IDENTITY_PROJECT, cloud));
        assertThat(projectStack.domain()).isNull();
        assertThat(projectStack.stack().toString()).isEqualTo(lower(cloud) + "-keycloak");
        assertThat(projectStack.egressIp()).isNull();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheStackOfTheIdentityFromTheFileIfItNamesOneOfItsCloud(AfonCloud cloud) {
        IdentityProjectStack projectStack = ProjectStackFixtures.identity(render(IDENTITY_PROJECT, cloud) + "stacks: {identity: {stack: " + lower(cloud) + "-sso}}\n");
        assertThat(projectStack.stack().toString()).isEqualTo(lower(cloud) + "-sso");
        assertThat(invalid(render(IDENTITY_PROJECT, cloud) + "stacks: {identity: {stack: " + lower(anotherThan(cloud)) + "-keycloak}}\n").problems())
            .anyMatch(p -> p.contains("is not on the project stack's cloud"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void rejectsWhatBelongsToATreProjectInAnIdentityProject(AfonCloud cloud) {
        String yaml = valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", role: identity}");
        assertThat(invalid(yaml).problems())
            .anyMatch(p -> p.startsWith("identity is not a known key"))
            .anyMatch(p -> p.startsWith("dataProviders is not a known key"))
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
        assertThat(FileProjectStackProvider.parse(valid(cloud)).role()).isEqualTo(ProjectStackRole.TRE);
        assertThat(FileProjectStackProvider.parse(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", role: tre}")).role())
            .isEqualTo(ProjectStackRole.TRE);
        assertThat(invalid(valid(cloud).replace("cloud: " + lower(cloud) + "}", "cloud: " + lower(cloud) + ", role: catalog}")).problems())
            .contains("projectStack.role 'catalog' is not a role of a project stack: one of [identity, tre, data]");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void plansTheIdentityProjectAsItsStackAloneNeverRebuiltAtNight(AfonCloud cloud) {
        String c = lower(cloud);
        ProjectStackPlan plan = ProjectStackPlan.of(FileProjectStackProvider.parse(ProjectStackFixtures.identityExample(cloud)));
        assertThat(plan.stages()).extracting(ProjectStackPlan.Stage::id).containsExactly(ProjectStackPlan.StageId.CREATE, ProjectStackPlan.StageId.IDENTITY);
        assertThat(plan.stages()).extracting(ProjectStackPlan.Stage::stateKey).containsExactly("idp/" + c + "-create", "idp/" + c + "-keycloak");
        assertThat(plan.nightlyStages()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void readsADataProjectWithItsCatalogsAndThePolarisStackOfItsCloud(AfonCloud cloud) {
        DataProjectStack projectStack = ProjectStackFixtures.data(ProjectStackFixtures.dataExample(cloud));
        assertThat(projectStack.id().value()).isEqualTo("lon");
        assertThat(projectStack.role()).isEqualTo(ProjectStackRole.DATA);
        assertThat(projectStack.cloud()).isEqualTo(cloud);
        assertThat(projectStack.catalogs()).containsExactly("cvd", "dbt");
        assertThat(projectStack.stack().toString()).isEqualTo(lower(cloud) + "-polaris");
        assertThat(projectStack.domain()).isNull();
        assertThat(projectStack.egressIp()).isEqualTo("203.0.113.40");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheStackAndTheDomainOfADataProjectFromItsFile(AfonCloud cloud) {
        DataProjectStack projectStack = ProjectStackFixtures.data(ProjectStackFixtures.dataExample(cloud)
            + "stacks:\n  data: {stack: " + lower(cloud) + "-polaris, domain: polaris.lon.example.org}\n");
        assertThat(projectStack.domain()).isEqualTo("polaris.lon.example.org");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesADataProjectWithNoCatalogOrAnInvalidOne(AfonCloud cloud) {
        String base = ProjectStackFixtures.dataExample(cloud);
        assertThat(invalid(base.replace("catalogs: [cvd, dbt]\n", "")).getMessage()).contains("catalogs is missing");
        assertThat(invalid(base.replace("catalogs: [cvd, dbt]", "catalogs: []")).getMessage()).contains("catalogs must be a non-empty list");
        assertThat(invalid(base.replace("[cvd, dbt]", "[Cvd]")).getMessage()).contains("catalogs[0] 'Cvd' is not a catalog id");
        assertThat(invalid(base.replace("[cvd, dbt]", "[cvd, cvd]")).getMessage()).contains("catalogs[1] 'cvd' is the id of another catalog");
        assertThat(invalid(base.replace("[cvd, dbt]", "[1cvd]")).getMessage()).contains("is not a catalog id");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesWhatADataProjectDoesNotHave(AfonCloud cloud) {
        String base = ProjectStackFixtures.dataExample(cloud);
        // it has no members, Data Providers or identity of its own, and no TRE stacks
        assertThat(invalid(base + "members: [ada@example.org]\n").getMessage()).contains("members");
        assertThat(invalid(base + "dataProviders: []\n").getMessage()).contains("dataProviders");
        assertThat(invalid(base + "stacks:\n  tre: {domains: {}}\n").getMessage()).contains("stacks.tre");
        assertThat(invalid(base + "stacks:\n  data: {stack: " + lower(anotherThan(cloud)) + "-polaris}\n").getMessage())
            .contains("is not on the project stack's cloud");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesCatalogsInAProjectThatIsNotAData(AfonCloud cloud) {
        assertThat(invalid(valid(cloud) + "catalogs: [cvd]\n").getMessage()).contains("catalogs");
        assertThat(invalid(ProjectStackFixtures.identityExample(cloud) + "catalogs: [cvd]\n").getMessage()).contains("catalogs");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void readsTheIdentityThatADataProjectTrustsWithTheAudienceOfItsOwnByDefault(AfonCloud cloud) {
        DataProjectStack projectStack = ProjectStackFixtures.data(ProjectStackFixtures.dataExample(cloud));
        assertThat(projectStack.identity().issuerUrl()).isEqualTo("https://id.example.org/realms/polaris");
        assertThat(projectStack.identity().acr()).isEqualTo("mfa");
        assertThat(projectStack.identity().audience()).isEqualTo("provider-lon");
        assertThat(projectStack.identity().projectStack()).isNull();
        assertThat(projectStack.identityProjectStack()).isNull();
        DataProjectStack named = ProjectStackFixtures.data(ProjectStackFixtures.dataExample(cloud).replace("acr: mfa}", "acr: mfa, audience: lon-data, projectStack: idp}"));
        assertThat(named.identity().audience()).isEqualTo("lon-data");
        assertThat(named.identityProjectStack()).isEqualTo("idp");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesADataProjectWithNoIdentityOrAnInsecureOne(AfonCloud cloud) {
        String base = ProjectStackFixtures.dataExample(cloud);
        assertThat(invalid(base.replace("identity: {issuerUrl: \"https://id.example.org/realms/polaris\", acr: mfa}\n", "")).problems())
            .contains("identity.issuerUrl is required", "identity.acr is required");
        assertThat(invalid(base.replace("https://id", "http://id")).problems()).contains("identity.issuerUrl must be an https URL, as every consumer pins it");
        assertThat(invalid(base.replace("acr: mfa}", "acr: mfa, clients: {}}")).problems()).anyMatch(p -> p.startsWith("identity.clients is not a known key"));
    }
}
