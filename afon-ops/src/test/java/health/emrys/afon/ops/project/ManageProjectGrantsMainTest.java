package health.emrys.afon.ops.project;

import health.emrys.afon.polaris.config.PolarisProjectGrants;
import health.emrys.afon.ops.kube.tools.PolarisAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a Data Provider grants a project, by the command: it calls Polaris only for an action it understands.
 */
class ManageProjectGrantsMainTest {

    private static final String ALICE = "11111111-aaaa-bbbb-cccc-000000000001";
    private static final String BOB = "22222222-aaaa-bbbb-cccc-000000000002";

    @TempDir
    Path dir;

    private final PolarisProjectGrants grants = mock(PolarisProjectGrants.class);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private ManageProjectGrantsMain command;
    private boolean polarisCalled;

    /** The emails that the project's file lists, or null for a project that this home has no file of. */
    private List<String> listed;

    @BeforeEach
    void command() {
        command = new ManageProjectGrantsMain(() -> {
            polarisCalled = true;
            return grants;
        }, new PrintStream(out), new PrintStream(err), dir, project -> listed);
    }

    private void membersFileOfTheProject(String content) throws IOException {
        Path file = ProjectMembers.file(dir, "cvd-2026");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Path members(String content) throws IOException {
        return Files.writeString(dir.resolve("members.txt"), content);
    }

    @Test
    void grantsTheProjectTheNamespaceAndMakesItsMembersThoseTheFileListsWithoutBlanksCommentsOrRepeats() throws Exception {
        Path file = members("# the contract's members\n" + ALICE + "\n\n  " + BOB + "  \n" + ALICE + "\n");
        when(grants.grant(anyString(), anyString(), anyString(), any()))
            .thenReturn(new PolarisProjectGrants.Members(List.of("afon:" + ALICE), List.of("afon:old")));

        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd", "--members", file.toString()})).isZero();

        verify(grants).grant("cvd-2026", "cvd", "released", List.of(ALICE, BOB));
        assertThat(out.toString()).contains("Project cvd-2026 may read cvd.released, read-only, and has 2 member(s)", "Added afon:" + ALICE, "Removed afon:old");
    }

    @Test
    void grantsFromTheMembersFileOfTheProjectIfNoneIsGiven() throws Exception {
        Path file = ProjectMembers.file(dir, "cvd-2026");
        Files.createDirectories(file.getParent());
        // as manage-identity writes it: a comment, and a line for each member with the email after the user id
        Files.writeString(file, "# the members\n" + ALICE + ",alice@tre.example\n" + BOB + ",bob@tre.example\n");
        when(grants.grant(anyString(), anyString(), anyString(), any())).thenReturn(new PolarisProjectGrants.Members(List.of(), List.of()));

        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd"})).isZero();

        verify(grants).grant("cvd-2026", "cvd", "released", List.of(ALICE, BOB));
    }

    @Test
    void grantsFromTheMembersFileWhenItIsTheMembersTheProjectFileLists() throws Exception {
        membersFileOfTheProject(ALICE + ",Alice@Tre.example\n" + BOB + ",bob@tre.example\n");
        listed = List.of("alice@tre.example", "BOB@tre.example");
        when(grants.grant(anyString(), anyString(), anyString(), any())).thenReturn(new PolarisProjectGrants.Members(List.of(), List.of()));

        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd"})).isZero();

        verify(grants).grant("cvd-2026", "cvd", "released", List.of(ALICE, BOB));
    }

    @Test
    void refusesToGrantFromAMembersFileThatDiffersFromTheMembersTheProjectFileLists() throws Exception {
        // bob is listed and not yet synced, and carol is in the file and is not listed
        membersFileOfTheProject(ALICE + ",alice@tre.example\n33333333-aaaa-bbbb-cccc-000000000003,carol@tre.example\n");
        listed = List.of("alice@tre.example", "bob@tre.example");

        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd"})).isEqualTo(1);

        assertThat(err.toString()).contains("not in the members file: bob@tre.example", "in the members file, not listed: carol@tre.example",
                                            "manage-identity sync-members");
        assertThat(polarisCalled).isFalse();
    }

    @Test
    void doesNotCheckAFileGivenNorAProjectThatThisHomeHasNoFileOf() throws Exception {
        when(grants.grant(anyString(), anyString(), anyString(), any())).thenReturn(new PolarisProjectGrants.Members(List.of(), List.of()));
        // a file that is given is what is asked for
        listed = List.of("someone.else@tre.example");
        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd", "--members", members(ALICE + "\n").toString()})).isZero();
        // and a TRE that Afon did not deploy has no file to compare with
        listed = null;
        membersFileOfTheProject(ALICE + ",alice@tre.example\n");
        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd"})).isZero();
    }

    @Test
    void aFileGivenWinsOverTheMembersFileOfTheProject() throws Exception {
        Path file = ProjectMembers.file(dir, "cvd-2026");
        Files.createDirectories(file.getParent());
        Files.writeString(file, BOB + ",bob@tre.example\n");
        when(grants.grant(anyString(), anyString(), anyString(), any())).thenReturn(new PolarisProjectGrants.Members(List.of(), List.of()));

        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd", "--members", members(ALICE + "\n").toString()})).isZero();

        verify(grants).grant("cvd-2026", "cvd", "released", List.of(ALICE));
    }

    @Test
    void saysWhereTheMembersFileOfTheProjectComesFromWhenThereIsNone() {
        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd"})).isEqualTo(1);
        assertThat(err.toString()).contains("members.csv", "manage-identity");
    }

    @Test
    void grantsAnotherNamespaceIfGiven() throws Exception {
        Path file = members(ALICE + "\n");
        when(grants.grant(anyString(), anyString(), anyString(), any())).thenReturn(new PolarisProjectGrants.Members(List.of(), List.of()));
        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd", "--members", file.toString(), "--namespace", "cvd_2026"})).isZero();
        verify(grants).grant("cvd-2026", "cvd", "cvd_2026", List.of(ALICE));
    }

    @Test
    void refusesAFileThatIsNotThereOrListsNoMember() throws Exception {
        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd", "--members", dir.resolve("none.txt").toString()})).isEqualTo(1);
        assertThat(err.toString()).contains("No file");
        err.reset();
        assertThat(command.run(new String[]{"grant", "cvd-2026", "cvd", "--members", members("# nobody\n\n").toString()})).isEqualTo(1);
        assertThat(err.toString()).contains("lists no member", "use revoke");
        verify(grants, never()).grant(anyString(), anyString(), anyString(), any());
    }

    @Test
    void releasingATableThatIsReleasedAlreadySaysSoAndSucceeds() throws Exception {
        when(grants.release("cvd", "patients", "released")).thenReturn(false);

        assertThat(command.run(new String[]{"release", "cvd", "patients"})).isZero();

        assertThat(out.toString()).contains("patients of cvd is in released already, and not in staging: nothing to move").doesNotContain("Moved");
    }

    @Test
    void addsAndRemovesAMemberAndRevokesAndReleases() throws Exception {
        when(grants.revoke("cvd-2026", "cvd")).thenReturn(List.of("afon:" + ALICE));
        when(grants.release("cvd", "patients", "released")).thenReturn(true);

        assertThat(command.run(new String[]{"add-member", "cvd-2026", ALICE})).isZero();
        assertThat(command.run(new String[]{"remove-member", "cvd-2026", BOB})).isZero();
        assertThat(command.run(new String[]{"revoke", "cvd-2026", "cvd"})).isZero();
        assertThat(command.run(new String[]{"release", "cvd", "patients"})).isZero();

        verify(grants).addMember("cvd-2026", ALICE);
        verify(grants).removeMember("cvd-2026", BOB);
        verify(grants).release("cvd", "patients", "released");
        assertThat(out.toString()).contains("Added afon:" + ALICE + " to project cvd-2026", "Removed afon:" + BOB + " from project cvd-2026",
                                            "Project cvd-2026 may no longer read cvd; 1 member(s) lost its role", "Moved patients of cvd from staging to released");
    }

    @Test
    void saysWhyPolarisRefusedAndFails() throws Exception {
        doThrow(new IOException("Polaris could not make the principal role cvd-2026Project: HTTP 403")).when(grants).addMember(anyString(), anyString());
        assertThat(command.run(new String[]{"add-member", "cvd-2026", ALICE})).isEqualTo(1);
        assertThat(err.toString()).contains("HTTP 403");
        err.reset();
        doThrow(new IllegalArgumentException("'x' is not the id of a user")).when(grants).removeMember(anyString(), anyString());
        assertThat(command.run(new String[]{"remove-member", "cvd-2026", "x"})).isEqualTo(1);
        assertThat(err.toString()).contains("is not the id of a user");
    }

    @Test
    void reachesPolarisThroughAPortForwardUnlessTheConfigSaysHttps() {
        assertThat(PolarisAccess.viaPortForward(null)).isTrue();
        assertThat(PolarisAccess.viaPortForward("  ")).isTrue();
        assertThat(PolarisAccess.viaPortForward("port-forward")).isTrue();
        assertThat(PolarisAccess.viaPortForward("PORT-FORWARD")).isTrue();
        assertThat(PolarisAccess.viaPortForward("https")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PolarisAccess.viaPortForward("ftp"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("POLARIS_ACCESS").hasMessageContaining("'ftp'");
    }

    @Test
    void callsPolarisOnlyForAnActionItUnderstands() {
        assertThat(command.run(new String[]{})).isZero();
        assertThat(out.toString()).contains("Usage: manage-project-grants");
        // the members file is the project's own if none is given, so a project and a catalog are all that it needs
        assertThat(command.run(new String[]{"grant", "cvd-2026"})).isEqualTo(2);
        assertThat(command.run(new String[]{"add-member", "cvd-2026"})).isEqualTo(2);
        assertThat(command.run(new String[]{"revoke", "cvd-2026", "cvd", "--namespace", "x"})).isEqualTo(2);
        assertThat(command.run(new String[]{"release", "cvd"})).isEqualTo(2);
        assertThat(command.run(new String[]{"dance"})).isEqualTo(2);
        assertThat(command.run(new String[]{"grant", "--bogus", "x"})).isEqualTo(2);
        assertThat(polarisCalled).isFalse();
        verifyNoInteractions(grants);
    }

    @Test
    void readsTheMembersOfAFile() throws Exception {
        assertThat(ManageProjectGrantsMain.members(members(ALICE + "\n# c\n" + BOB + "\n"))).containsExactly(ALICE, BOB);
        assertThatThrownBy(() -> ManageProjectGrantsMain.members(dir.resolve("absent"))).isInstanceOf(IOException.class);
    }

    @Test
    void showsWhatEachCatalogHoldsAndWhatTheProjectsHaveWithTheEmailsOfTheirMembers() throws Exception {
        membersFileOfTheProject(ALICE + ",alice@tre.example\n");
        when(grants.show(null)).thenReturn(new PolarisProjectGrants.Overview(
            List.of(new PolarisProjectGrants.CatalogContents("cvd", java.util.Map.of("released", List.of("smoke_test")))),
            List.of(new PolarisProjectGrants.ProjectAccess("cvd-2026", "cvd-2026Project", List.of("afon:" + ALICE, "afon:" + BOB),
                                                           List.of(new PolarisProjectGrants.CatalogAccess("cvd", "cvd-2026Reader", List.of("read-only on released")))))));

        assertThat(command.run(new String[]{"show"})).isZero();

        assertThat(out.toString()).contains("Catalogs:", "cvd: released [smoke_test]", "Project cvd-2026 (principal role cvd-2026Project)",
                                            "members (2): afon:" + ALICE + " (alice@tre.example), afon:" + BOB,
                                            "cvd: catalog role cvd-2026Reader, read-only on released");
        assertThat(out.toString()).doesNotContain("afon:" + BOB + " (");
    }

    @Test
    void showsOneProjectAndSaysSoWhenItHasNothingHere() throws Exception {
        when(grants.show("diab-2026")).thenReturn(new PolarisProjectGrants.Overview(List.of(), List.of()));

        assertThat(command.run(new String[]{"show", "diab-2026"})).isZero();

        assertThat(out.toString()).contains("Project diab-2026 has been granted nothing here");
        verify(grants).show("diab-2026");
    }

    @Test
    void showTakesAtMostAProject() {
        assertThat(command.run(new String[]{"show", "a", "b"})).isEqualTo(2);
        assertThat(command.run(new String[]{"show", "--namespace", "x"})).isEqualTo(2);
        verifyNoInteractions(grants);
    }
}
