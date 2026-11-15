package health.emrys.afon.ops.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.project.AfonProject;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every test is run on every cloud, see {@link ProjectTemplates}.
 */
class ManageIdentityMainTest {

    private static final String PROJECT = "cvd-2026-0007";

    private final IdentityProvider identity = mock(IdentityProvider.class);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    @TempDir
    Path home;
    private ManageIdentityMain command;

    /** Writes the project of the example on the cloud, and makes the command that acts on it. */
    private void setUp(AfonCloud cloud) throws Exception {
        ProjectTemplates.write(home, PROJECT, cloud);
        command = new ManageIdentityMain(project -> identity, new PrintStream(out), new PrintStream(err), home);
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
        ArgumentCaptor<AfonProject> project = ArgumentCaptor.forClass(AfonProject.class);
        verify(identity).ensureProject(project.capture(), captured.capture());
        assertThat(project.getValue().id().value()).isEqualTo("cvd-2026-0007");
        assertThat(captured.getValue()).containsOnlyKeys("tre", "git");
        assertThat(out.toString()).contains("cvd-2026-0007-tre", "cvd-2026-0007-git");
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
        verify(identity).ensureProject(any(AfonProject.class), captured.capture());
        assertThat(captured.getValue()).containsOnlyKeys("tre", "git");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    @SuppressWarnings("unchecked")
    void needsOnlyTheTreKeyForAProjectWithNoGitOfItsOwn(AfonCloud cloud) throws Exception {
        setUp(cloud);
        String yaml = ProjectTemplates.example(cloud).replace("    gitBucket: cvd-2026-0007-gitea\n", "")
                           .replace("  git:\n    domain: git.cvd-2026-0007.example.org\n", "");
        Files.writeString(home.resolve("projects/" + PROJECT + "/project.yaml"), yaml);
        Path keys = Files.createDirectories(home.resolve("projects/" + PROJECT + "/keys"));
        Files.writeString(keys.resolve("tre.jwk"), "{\"kty\":\"RSA\",\"n\":\"a\",\"e\":\"AQAB\"}");
        assertThat(command.run(new String[]{"create", PROJECT})).isZero();
        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        verify(identity).ensureProject(any(AfonProject.class), captured.capture());
        assertThat(captured.getValue()).containsOnlyKeys("tre");
        assertThat(out.toString()).contains("client cvd-2026-0007-tre, and a group").doesNotContain("-git");
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
        when(identity.ensureUser("alice@uni.example", "Alice", null)).thenReturn("the-user-id");
        assertThat(command.run(new String[]{"add-member", PROJECT, "alice@uni.example", "--first-name", "Alice"})).isZero();
        verify(identity).addMember(any(AfonProject.class), eq("the-user-id"));
        assertThat(out.toString().trim()).isEqualTo("the-user-id");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void removesAMemberByEmail(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.findUser("alice@uni.example")).thenReturn(Optional.of("the-user-id"));
        assertThat(command.run(new String[]{"remove-member", PROJECT, "alice@uni.example"})).isZero();
        verify(identity).removeMember(any(AfonProject.class), eq("the-user-id"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void failsToRemoveAnUnknownUser(AfonCloud cloud) throws Exception {
        setUp(cloud);
        when(identity.findUser("bob@uni.example")).thenReturn(Optional.empty());
        assertThat(command.run(new String[]{"remove-member", PROJECT, "bob@uni.example"})).isEqualTo(1);
        assertThat(err.toString()).contains("No user bob@uni.example");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void closesTheProject(AfonCloud cloud) throws Exception {
        setUp(cloud);
        assertThat(command.run(new String[]{"close", PROJECT})).isZero();
        verify(identity).closeProject(any(AfonProject.class));
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
}
