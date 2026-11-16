package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.project.TreProject;
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
 * Every test is run on every cloud, see {@link ProjectTemplates}.
 */
class ManageIdentityMainTest {

    private static final String PROJECT = "xyz-yyyy-nnnn";

    private final IdentityProvider identity = mock(IdentityProvider.class);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    @TempDir
    Path home;
    private ManageIdentityMain command;
    /** The project whose home it runs in, if it does. */
    private String defaultProject;
    /** The endpoints that the identity was asked for, in order: {@code null} for its OIDC domain name. */
    private final java.util.List<String> endpoints = new java.util.ArrayList<>();
    /** What the tunnels were opened to, and whether they were closed. */
    private final java.util.List<String> tunnels = new java.util.ArrayList<>();

    /** Writes the project of the example on the cloud, and makes the command that acts on it. */
    private void setUp(AfonCloud cloud) throws Exception {
        ProjectTemplates.write(home, PROJECT, cloud);
        command = new ManageIdentityMain((project, endpoint) -> {
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
        }, () -> defaultProject, new PrintStream(out), new PrintStream(err), home);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    @SuppressWarnings("unchecked")
    void createsTheProjectWithThePublicKeysGiven(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createTempDirectory("keys");
        Files.writeString(keys.resolve("tre.jwk"), "{\"kty\":\"RSA\",\"n\":\"a\",\"e\":\"AQAB\"}");
        Files.writeString(keys.resolve("git.jwk"), "{\"kty\":\"RSA\",\"n\":\"b\",\"e\":\"AQAB\"}");
        assertThat(command.run(new String[]{"create", PROJECT, "--public-keys", keys.toString()})).isZero();
        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<TreProject> project = ArgumentCaptor.forClass(TreProject.class);
        verify(identity).ensureProject(project.capture(), captured.capture());
        assertThat(project.getValue().id().value()).isEqualTo("xyz-yyyy-nnnn");
        assertThat(captured.getValue()).containsOnlyKeys("tre", "git");
        assertThat(out.toString()).contains("xyz-yyyy-nnnn-tre", "xyz-yyyy-nnnn-git");
    }

    private void listMembers(AfonCloud cloud, String members) throws Exception {
        setUp(cloud);
        Path file = home.resolve("projects/" + PROJECT + "/project.yaml");
        Files.writeString(file, Files.readString(file) + members);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void syncsTheGroupToTheMembersTheFileLists(AfonCloud cloud) throws Exception {
        listMembers(cloud, "members:\n  - ada@example.org\n  - {email: grace@example.org, firstName: Grace, lastName: Hopper}\n");
        when(identity.members(any(TreProject.class))).thenReturn(Map.of("id-ada", "ada@example.org", "id-old", "old@example.org"));
        when(identity.ensureUser("ada@example.org", null, null)).thenReturn("id-ada");
        when(identity.ensureUser("grace@example.org", "Grace", "Hopper")).thenReturn("id-grace");
        assertThat(command.run(new String[]{"sync-members", PROJECT})).isZero();
        verify(identity, never()).addMember(any(TreProject.class), eq("id-ada"));
        verify(identity).addMember(any(TreProject.class), eq("id-grace"));
        verify(identity).removeMember(any(TreProject.class), eq("id-old"));
        assertThat(out.toString()).contains("Added grace@example.org (id-grace)", "Removed old@example.org (id-old)", "has 2 members");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void emptiesTheGroupOfAFileWithNoMembers(AfonCloud cloud) throws Exception {
        listMembers(cloud, "members: []\n");
        when(identity.members(any(TreProject.class))).thenReturn(Map.of("id-old", "old@example.org"));
        assertThat(command.run(new String[]{"sync-members", PROJECT})).isZero();
        verify(identity).removeMember(any(TreProject.class), eq("id-old"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void leavesTheGroupAsItIsIfTheFileListsNoMembers(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"sync-members", PROJECT})).isZero();
        verify(identity, never()).members(any(TreProject.class));
        verify(identity, never()).removeMember(any(TreProject.class), any());
        assertThat(out.toString()).contains("lists no members");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    @SuppressWarnings("unchecked")
    void createsWithTheKeysOfTheProjectDirByDefault(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT + "/keys"));
        Files.writeString(keys.resolve("tre.jwk"), "{\"kty\":\"RSA\",\"n\":\"a\",\"e\":\"AQAB\"}");
        Files.writeString(keys.resolve("git.jwk"), "{\"kty\":\"RSA\",\"n\":\"b\",\"e\":\"AQAB\"}");
        assertThat(command.run(new String[]{"create", PROJECT})).isZero();
        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        verify(identity).ensureProject(any(TreProject.class), captured.capture());
        assertThat(captured.getValue()).containsOnlyKeys("tre", "git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    @SuppressWarnings("unchecked")
    void needsOnlyTheTreKeyForAProjectWithNoGitOfItsOwn(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String yaml = ProjectTemplates.example(cloud).replace("    gitBucket: xyz-yyyy-nnnn-gitea\n", "")
                           .replace("  git:\n    domain: git.xyz-yyyy-nnnn.example.org\n", "");
        Files.writeString(home.resolve("projects/" + PROJECT + "/project.yaml"), yaml);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT + "/keys"));
        Files.writeString(keys.resolve("tre.jwk"), "{\"kty\":\"RSA\",\"n\":\"a\",\"e\":\"AQAB\"}");
        assertThat(command.run(new String[]{"create", PROJECT})).isZero();
        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        verify(identity).ensureProject(any(TreProject.class), captured.capture());
        assertThat(captured.getValue()).containsOnlyKeys("tre");
        assertThat(out.toString()).contains("client xyz-yyyy-nnnn-tre, and a group").doesNotContain("-git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsWithoutKeysInTheProjectDir(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"create", PROJECT})).isEqualTo(1);
        assertThat(err.toString()).contains("No public key", "tre.jwk", "keys dir");
        verifyNoInteractions(identity);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsWhenAKeyIsMissing(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createTempDirectory("keys");
        assertThat(command.run(new String[]{"create", PROJECT, "--public-keys", keys.toString()})).isEqualTo(1);
        assertThat(err.toString()).contains("No public key", "tre.jwk");
        verifyNoInteractions(identity);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void addsAMemberMakingTheUserAndPrintsTheirId(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.ensureUser("alice@tre.example", "Alice", null)).thenReturn("the-user-id");
        assertThat(command.run(new String[]{"add-member", PROJECT, "alice@tre.example", "--first-name", "Alice"})).isZero();
        verify(identity).addMember(any(TreProject.class), eq("the-user-id"));
        assertThat(out.toString().trim()).isEqualTo("the-user-id");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void removesAMemberByEmail(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.findUser("alice@tre.example")).thenReturn(Optional.of("the-user-id"));
        assertThat(command.run(new String[]{"remove-member", PROJECT, "alice@tre.example"})).isZero();
        verify(identity).removeMember(any(TreProject.class), eq("the-user-id"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToRemoveAnUnknownUser(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.findUser("bob@tre.example")).thenReturn(Optional.empty());
        assertThat(command.run(new String[]{"remove-member", PROJECT, "bob@tre.example"})).isEqualTo(1);
        assertThat(err.toString()).contains("No user bob@tre.example");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void closesTheProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"close", PROJECT})).isZero();
        verify(identity).closeProject(any(TreProject.class));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void showsTheUsageForAWrongActionOrOption(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"delete", PROJECT})).isEqualTo(2);
        assertThat(command.run(new String[]{"close", PROJECT, "--bogus", "x"})).isEqualTo(2);
        assertThat(err.toString()).contains("Unknown action 'delete'", "Usage: manage-identity");
        assertThat(command.run(new String[0])).isZero();
        assertThat(out.toString()).contains("Usage: manage-identity");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesToActOnTheIdentityProjectItself(AfonCloud cloud) throws Exception {
        setUp(cloud);
        ProjectTemplates.writeIdentity(home, "idp", cloud);
        assertThat(command.run(new String[]{"close", "idp"})).isEqualTo(1);
        assertThat(err.toString()).contains("Project idp is the central identity itself", "no clients, group or members");
        verifyNoInteractions(identity);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void reachesKeycloakAtItsDomainNameUnlessGivenAKubeconfig(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT + "/keys"));
        Files.writeString(keys.resolve("tre.jwk"), "{\"kty\":\"RSA\",\"n\":\"a\",\"e\":\"AQAB\"}");
        Files.writeString(keys.resolve("git.jwk"), "{\"kty\":\"RSA\",\"n\":\"b\",\"e\":\"AQAB\"}");
        assertThat(command.run(new String[]{"create", PROJECT})).isZero();
        assertThat(endpoints).containsExactly((String) null);
        assertThat(tunnels).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void reachesKeycloakThroughAPortForwardInTheClusterOfTheKubeconfig(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT + "/keys"));
        Files.writeString(keys.resolve("tre.jwk"), "{\"kty\":\"RSA\",\"n\":\"a\",\"e\":\"AQAB\"}");
        Files.writeString(keys.resolve("git.jwk"), "{\"kty\":\"RSA\",\"n\":\"b\",\"e\":\"AQAB\"}");
        Path kubeConfig = Files.writeString(home.resolve("identity.config"), "apiVersion: v1\n");
        assertThat(command.run(new String[]{"create", PROJECT, "--kubeconfig", kubeConfig.toString()})).isZero();
        assertThat(endpoints).containsExactly("localhost:54321");
        assertThat(tunnels).containsExactly("open " + cloud + " identity.config", "close");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void closesTheTunnelWhenTheActionFails(AfonCloud cloud) throws Exception {
        setUp(cloud);
        Path kubeConfig = Files.writeString(home.resolve("identity.config"), "apiVersion: v1\n");
        // no public keys, so that the creation fails
        assertThat(command.run(new String[]{"create", PROJECT, "--kubeconfig", kubeConfig.toString()})).isEqualTo(1);
        assertThat(err.toString()).contains("No public key");
        assertThat(tunnels).endsWith("close");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAKubeconfigThatIsNotThere(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"close", PROJECT, "--kubeconfig", home.resolve("none.config").toString()})).isEqualTo(1);
        assertThat(err.toString()).contains("No kubeconfig");
        assertThat(tunnels).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void takesTheProjectOfTheHomeItRunsInWhenNoneIsGiven(AfonCloud cloud) throws Exception {
        setUp(cloud);
        defaultProject = PROJECT;
        assertThat(command.run(new String[]{"close"})).isZero();
        verify(identity).closeProject(org.mockito.ArgumentMatchers.argThat(p -> p.id().value().equals(PROJECT)));
        when(identity.findUser("ann@example.org")).thenReturn(Optional.of("u-1"));
        assertThat(command.run(new String[]{"remove-member", "ann@example.org"})).isZero();
        assertThat(out.toString()).contains("Removed ann@example.org (u-1) from project " + PROJECT);
        // a project that is given is the one that is meant
        defaultProject = "another-project";
        assertThat(command.run(new String[]{"close", PROJECT})).isZero();
        verify(identity, org.mockito.Mockito.times(2)).closeProject(org.mockito.ArgumentMatchers.argThat(p -> p.id().value().equals(PROJECT)));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void needsAProjectWhenItIsNotRunInTheHomeOfOne(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"close"})).isEqualTo(2);
        assertThat(err.toString()).contains("close takes a <project>, unless it is run in the home of one");
        assertThat(command.run(new String[]{"remove-member", "ann@example.org"})).isEqualTo(2);
    }
}
