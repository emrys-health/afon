package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.project.TreProjectStack;
import health.emrys.afon.security.IdentityProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every test is run on every cloud, see {@link ProjectStackTemplates}.
 */
class ManageIdentityMainTest {

    private static final String PROJECT_STACK = "xyz-yyyy-nnnn";

    private final IdentityProvider identity = mock(IdentityProvider.class);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    @TempDir
    Path home;
    private ManageIdentityMain command;
    /** The project stack whose home it runs in, if it does. */
    private String defaultProjectStack;
    /** The endpoints that the identity was asked for, in order: {@code null} for its OIDC domain name. */
    private final java.util.List<String> endpoints = new java.util.ArrayList<>();
    /** What the tunnels were opened to, and whether they were closed. */
    private final java.util.List<String> tunnels = new java.util.ArrayList<>();

    /** Writes the project stack of the example on the cloud, and makes the command that acts on it. */
    private void setUp(AfonCloud cloud) throws Exception {
        ProjectStackTemplates.write(home, PROJECT_STACK, cloud);
        command = new ManageIdentityMain((projectStack, endpoint) -> {
            endpoints.add(endpoint);
            return identity;
        }, (tunnelCloud, kubeConfig, call) -> {
            if (kubeConfig == null) {
                // nothing names the cluster of Keycloak: it is reached at its domain name
                call.run(null);
                return;
            }
            tunnels.add("open " + tunnelCloud + " " + kubeConfig.getFileName());
            try {
                call.run("localhost:54321");
            } finally {
                tunnels.add("close");
            }
        }, () -> defaultProjectStack, new PrintStream(out), new PrintStream(err), home);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    @SuppressWarnings("unchecked")
    void createsTheProjectWithTheSecretsGiven(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createTempDirectory("keys");
        Files.writeString(keys.resolve("tre.secret"), "a-secret");
        Files.writeString(keys.resolve("git.secret"), "b-secret");
        assertThat(command.run(new String[]{"create", PROJECT_STACK, "--secrets", keys.toString()})).isZero();
        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<TreProjectStack> projectStack = ArgumentCaptor.forClass(TreProjectStack.class);
        verify(identity).ensureProject(projectStack.capture(), captured.capture());
        assertThat(projectStack.getValue().id().value()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(captured.getValue()).containsOnlyKeys("tre", "git").containsEntry("tre", "a-secret").containsEntry("git", "b-secret");
        assertThat(out.toString()).contains("xyz-yyyy-nnnn-tre", "xyz-yyyy-nnnn-git");
    }

    private void listMembers(AfonCloud cloud, String members) throws Exception {
        setUp(cloud);
        Path file = home.resolve("projects/" + PROJECT_STACK + "/project.yaml");
        Files.writeString(file, Files.readString(file).replace("members: [ada@example.org]\n", members));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void syncsTheGroupToTheMembersTheFileLists(AfonCloud cloud) throws Exception {
        listMembers(cloud, "members:\n  - ada@example.org\n  - {email: grace@example.org, firstName: Grace, lastName: Hopper}\n");
        when(identity.members(any(TreProjectStack.class))).thenReturn(Map.of("id-ada", "ada@example.org", "id-old", "old@example.org"));
        when(identity.ensureUser("ada@example.org", null, null, "xyz-yyyy-nnnn-tre")).thenReturn("id-ada");
        when(identity.ensureUser("grace@example.org", "Grace", "Hopper", "xyz-yyyy-nnnn-tre")).thenReturn("id-grace");
        assertThat(command.run(new String[]{"sync-members", PROJECT_STACK})).isZero();
        verify(identity, never()).addMember(any(TreProjectStack.class), eq("id-ada"));
        verify(identity).addMember(any(TreProjectStack.class), eq("id-grace"));
        verify(identity).removeMember(any(TreProjectStack.class), eq("id-old"));
        assertThat(out.toString()).contains("Added grace@example.org (id-grace)", "Removed old@example.org (id-old)", "has 2 members");
        // the ids of the group are kept in the project stack's dir, as the group is
        assertThat(ProjectMembers.read(ProjectMembers.file(home, PROJECT_STACK))).containsExactly(
            Map.entry("id-ada", "ada@example.org"), Map.entry("id-grace", "grace@example.org"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    @SuppressWarnings("unchecked")
    void createsWithTheKeysOfTheProjectDirByDefault(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT_STACK + "/secrets"));
        Files.writeString(keys.resolve("tre.secret"), "a-secret");
        Files.writeString(keys.resolve("git.secret"), "b-secret");
        assertThat(command.run(new String[]{"create", PROJECT_STACK})).isZero();
        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        verify(identity).ensureProject(any(TreProjectStack.class), captured.capture());
        assertThat(captured.getValue()).containsOnlyKeys("tre", "git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    @SuppressWarnings("unchecked")
    void needsOnlyTheTreKeyForAProjectWithNoGitOfItsOwn(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String yaml = ProjectStackTemplates.example(cloud).replace("    gitBucket: xyz-yyyy-nnnn-gitea\n", "")
                           .replace("  git:\n    domain: git.xyz-yyyy-nnnn.example.org\n", "");
        Files.writeString(home.resolve("projects/" + PROJECT_STACK + "/project.yaml"), yaml);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT_STACK + "/secrets"));
        Files.writeString(keys.resolve("tre.secret"), "a-secret");
        assertThat(command.run(new String[]{"create", PROJECT_STACK})).isZero();
        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        verify(identity).ensureProject(any(TreProjectStack.class), captured.capture());
        assertThat(captured.getValue()).containsOnlyKeys("tre");
        assertThat(out.toString()).contains("client xyz-yyyy-nnnn-tre, and a group").doesNotContain("-git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsWithoutKeysInTheProjectDir(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"create", PROJECT_STACK})).isEqualTo(1);
        assertThat(err.toString()).contains("No secret", "tre.secret", "secrets dir");
        verifyNoInteractions(identity);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsWhenAKeyIsMissing(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createTempDirectory("keys");
        assertThat(command.run(new String[]{"create", PROJECT_STACK, "--secrets", keys.toString()})).isEqualTo(1);
        assertThat(err.toString()).contains("No secret", "tre.secret");
        verifyNoInteractions(identity);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void addsAMemberMakingTheUserAndPrintsTheirId(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.ensureUser("alice@tre.example", "Alice", null, "xyz-yyyy-nnnn-tre")).thenReturn("the-user-id");
        assertThat(command.run(new String[]{"add-member", PROJECT_STACK, "alice@tre.example", "--first-name", "Alice"})).isZero();
        verify(identity).addMember(any(TreProjectStack.class), eq("the-user-id"));
        assertThat(out.toString().trim()).isEqualTo("the-user-id");
        assertThat(ProjectMembers.read(ProjectMembers.file(home, PROJECT_STACK))).containsExactly(Map.entry("the-user-id", "alice@tre.example"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void warnsThatAMemberAddedByHandWhoIsNotListedIsTakenOutByTheNextSync(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.ensureUser("alice@tre.example", null, null, "xyz-yyyy-nnnn-tre")).thenReturn("id-alice");
        when(identity.ensureUser("ADA@example.org", null, null, "xyz-yyyy-nnnn-tre")).thenReturn("id-ada");

        assertThat(command.run(new String[]{"add-member", PROJECT_STACK, "alice@tre.example"})).isZero();
        assertThat(err.toString()).contains("alice@tre.example is not listed in the members of " + PROJECT_STACK, "sync-members");
        err.reset();

        // a member that the project stack's file lists is not warned about
        assertThat(command.run(new String[]{"add-member", PROJECT_STACK, "ADA@example.org"})).isZero();
        assertThat(err.toString()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void keepsEachMemberAddedInTheMembersFileOfTheProjectOnce(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.ensureUser("alice@tre.example", null, null, "xyz-yyyy-nnnn-tre")).thenReturn("id-alice");
        when(identity.ensureUser("bob@tre.example", null, null, "xyz-yyyy-nnnn-tre")).thenReturn("id-bob");
        when(identity.ensureUser("Alice@TRE.example", null, null, "xyz-yyyy-nnnn-tre")).thenReturn("id-alice");
        assertThat(command.run(new String[]{"add-member", PROJECT_STACK, "alice@tre.example"})).isZero();
        assertThat(command.run(new String[]{"add-member", PROJECT_STACK, "bob@tre.example"})).isZero();
        assertThat(command.run(new String[]{"add-member", PROJECT_STACK, "Alice@TRE.example"})).isZero();

        assertThat(ProjectMembers.read(ProjectMembers.file(home, PROJECT_STACK))).containsExactly(
            Map.entry("id-alice", "alice@tre.example"), Map.entry("id-bob", "bob@tre.example"));
        // the file is the project stack's, where the grants of a Data Provider find it
        assertThat(Files.readString(home.resolve("projects/" + PROJECT_STACK + "/members.csv"))).contains("id-alice,alice@tre.example\n", "id-bob,bob@tre.example\n");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void removesAMemberByEmail(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.findUser("alice@tre.example")).thenReturn(Optional.of("the-user-id"));
        assertThat(command.run(new String[]{"remove-member", PROJECT_STACK, "alice@tre.example"})).isZero();
        verify(identity).removeMember(any(TreProjectStack.class), eq("the-user-id"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void forgetsARemovedMemberInTheMembersFile(AfonCloud cloud) throws Exception {
        setUp(cloud);
        ProjectMembers.write(ProjectMembers.file(home, PROJECT_STACK), new java.util.LinkedHashMap<>(Map.of("the-user-id", "alice@tre.example", "other-id", "o@tre.example")));
        when(identity.findUser("alice@tre.example")).thenReturn(Optional.of("the-user-id"));
        assertThat(command.run(new String[]{"remove-member", PROJECT_STACK, "alice@tre.example"})).isZero();

        assertThat(ProjectMembers.read(ProjectMembers.file(home, PROJECT_STACK))).containsExactly(Map.entry("other-id", "o@tre.example"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToRemoveAnUnknownUser(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.findUser("bob@tre.example")).thenReturn(Optional.empty());
        assertThat(command.run(new String[]{"remove-member", PROJECT_STACK, "bob@tre.example"})).isEqualTo(1);
        assertThat(err.toString()).contains("No user bob@tre.example");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void closesTheProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"close", PROJECT_STACK})).isZero();
        verify(identity).closeProject(any(TreProjectStack.class));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void showsTheUsageForAWrongActionOrOption(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"delete", PROJECT_STACK})).isEqualTo(2);
        assertThat(command.run(new String[]{"close", PROJECT_STACK, "--bogus", "x"})).isEqualTo(2);
        assertThat(err.toString()).contains("Unknown action 'delete'", "Usage: manage-identity");
        assertThat(command.run(new String[0])).isZero();
        assertThat(out.toString()).contains("Usage: manage-identity");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToActOnTheIdentityProjectItself(AfonCloud cloud) throws Exception {
        setUp(cloud);
        ProjectStackTemplates.writeIdentity(home, "idp", cloud);
        assertThat(command.run(new String[]{"close", "idp"})).isEqualTo(1);
        assertThat(err.toString()).contains("Project stack idp has the role identity", "no clients, group or members");
        verifyNoInteractions(identity);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void reachesKeycloakAtItsDomainNameUnlessGivenAKubeconfig(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT_STACK + "/secrets"));
        Files.writeString(keys.resolve("tre.secret"), "a-secret");
        Files.writeString(keys.resolve("git.secret"), "b-secret");
        assertThat(command.run(new String[]{"create", PROJECT_STACK})).isZero();
        assertThat(endpoints).containsExactly((String) null);
        assertThat(tunnels).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void reachesKeycloakThroughAPortForwardInTheClusterOfTheKubeconfig(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT_STACK + "/secrets"));
        Files.writeString(keys.resolve("tre.secret"), "a-secret");
        Files.writeString(keys.resolve("git.secret"), "b-secret");
        Path kubeConfig = Files.writeString(home.resolve("identity.config"), "apiVersion: v1\n");
        assertThat(command.run(new String[]{"create", PROJECT_STACK, "--kubeconfig", kubeConfig.toString()})).isZero();
        assertThat(endpoints).containsExactly("localhost:54321");
        assertThat(tunnels).containsExactly("open " + cloud + " identity.config", "close");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void closesTheTunnelWhenTheActionFails(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path kubeConfig = Files.writeString(home.resolve("identity.config"), "apiVersion: v1\n");
        // no secrets, so that the creation fails
        assertThat(command.run(new String[]{"create", PROJECT_STACK, "--kubeconfig", kubeConfig.toString()})).isEqualTo(1);
        assertThat(err.toString()).contains("No secret");
        assertThat(tunnels).endsWith("close");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAKubeconfigThatIsNotThere(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"close", PROJECT_STACK, "--kubeconfig", home.resolve("none.config").toString()})).isEqualTo(1);
        assertThat(err.toString()).contains("No kubeconfig");
        assertThat(tunnels).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheProjectOfTheHomeItRunsInWhenNoneIsGiven(AfonCloud cloud) throws Exception {
        setUp(cloud);
        defaultProjectStack = PROJECT_STACK;
        assertThat(command.run(new String[]{"close"})).isZero();
        verify(identity).closeProject(org.mockito.ArgumentMatchers.argThat(p -> p.id().value().equals(PROJECT_STACK)));
        when(identity.findUser("ann@example.org")).thenReturn(Optional.of("u-1"));
        assertThat(command.run(new String[]{"remove-member", "ann@example.org"})).isZero();
        assertThat(out.toString()).contains("Removed ann@example.org (u-1) from project stack " + PROJECT_STACK);
        // a project stack that is given is the one that is meant
        defaultProjectStack = "another-project";
        assertThat(command.run(new String[]{"close", PROJECT_STACK})).isZero();
        verify(identity, org.mockito.Mockito.times(2)).closeProject(org.mockito.ArgumentMatchers.argThat(p -> p.id().value().equals(PROJECT_STACK)));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void needsAProjectWhenItIsNotRunInTheHomeOfOne(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"close"})).isEqualTo(2);
        assertThat(err.toString()).contains("close takes a <project-stack>, unless it is run in the home of one");
        assertThat(command.run(new String[]{"remove-member", "ann@example.org"})).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void invitesAMemberAgain(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"invite", PROJECT_STACK, "ada@example.org"})).isZero();
        verify(identity).invite("ada@example.org", "xyz-yyyy-nnnn-tre");
        assertThat(out.toString()).contains("Sent the invitation to ada@example.org");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsWhenTheInvitationCannotBeSent(AfonCloud cloud) throws Exception {
        setUp(cloud);
        org.mockito.Mockito.doThrow(new IllegalStateException("Could not send the invitation email to ada@example.org (HTTP 500)"))
                           .when(identity).invite("ada@example.org", "xyz-yyyy-nnnn-tre");
        assertThat(command.run(new String[]{"invite", PROJECT_STACK, "ada@example.org"})).isEqualTo(1);
        assertThat(err.toString()).contains("Could not send the invitation email");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesOneEmailToInvite(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"invite", PROJECT_STACK})).isEqualTo(2);
        assertThat(command.run(new String[]{"invite", PROJECT_STACK, "a@example.org", "b@example.org"})).isEqualTo(2);
        verify(identity, never()).invite(any(), any());
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void removesTheAuthenticatorsOfAMemberAndSaysHowTheyEnrolANewOne(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.removeSecondFactor("ada@example.org")).thenReturn(2);

        assertThat(command.run(new String[]{"remove-authenticator", PROJECT_STACK, "ada@example.org"})).isZero();

        assertThat(out.toString()).contains("Removed 2 authenticators of ada@example.org", "manage-identity invite ada@example.org");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void saysThatAMemberWithNoAuthenticatorHasNoneToRemove(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.removeSecondFactor("ada@example.org")).thenReturn(0);

        assertThat(command.run(new String[]{"remove-authenticator", PROJECT_STACK, "ada@example.org"})).isZero();

        assertThat(out.toString()).contains("ada@example.org has no authenticator");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToRemoveTheAuthenticatorsOfAnUnknownUserAndTakesOneEmail(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.removeSecondFactor("bob@tre.example")).thenThrow(new IllegalStateException("No user bob@tre.example in the central identity"));

        assertThat(command.run(new String[]{"remove-authenticator", PROJECT_STACK, "bob@tre.example"})).isEqualTo(1);
        assertThat(err.toString()).contains("No user bob@tre.example");
        assertThat(command.run(new String[]{"remove-authenticator", PROJECT_STACK})).isEqualTo(2);
        assertThat(command.run(new String[]{"remove-authenticator", PROJECT_STACK, "a@example.org", "b@example.org"})).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void printsTheUserIdOfAMemberAloneAndChangesNothing(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.findUser("ada@example.org")).thenReturn(java.util.Optional.of("11111111-aaaa-bbbb-cccc-000000000001"));

        assertThat(command.run(new String[]{"user-id", PROJECT_STACK, "ada@example.org"})).isZero();

        assertThat(out.toString().strip()).isEqualTo("11111111-aaaa-bbbb-cccc-000000000001");
        verify(identity, never()).ensureUser(any(), any(), any(), any());
        verify(identity, never()).addMember(any(), any());
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToGiveTheUserIdOfAnUnknownUserAndTakesOneEmail(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.findUser("bob@tre.example")).thenReturn(java.util.Optional.empty());

        assertThat(command.run(new String[]{"user-id", PROJECT_STACK, "bob@tre.example"})).isEqualTo(1);
        assertThat(err.toString()).contains("No user bob@tre.example in the central identity");
        assertThat(command.run(new String[]{"user-id", PROJECT_STACK})).isEqualTo(2);
    }
}
