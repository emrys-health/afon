package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUser.Role;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FileUserProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static health.emrys.afon.project.ProjectFixtures.tre;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every test is run on every cloud, see {@link ProjectFixtures}.
 */
class ProjectUserProviderTest {

    private static final String USERS = """
        # username,password,email,slug,firstName,lastName
        root,p5ssw0rd,root@example.org,root,Root,Admin
        ada,p5ssw0rd,Ada@Example.org,ada,Ada,Lovelace
        grace@example.org,p5ssw0rd,grace@example.org,grace,Grace,Hopper
        """;

    @TempDir
    Path dir;

    private AfonUserProvider admins() throws IOException {
        Files.writeString(dir.resolve("users.csv"), USERS);
        return new FileUserProvider(dir.toString());
    }

    private static TreProject project(AfonCloud cloud, String members) {
        return tre(ProjectFixtures.render("""
            project: {id: xyz-yyyy-nnnn, cloud: ${cloud}}
            identity: {issuerUrl: "https://id.example.org/realms/polaris", acr: mfa}
            providers:
              - {id: data1, catalogUri: "https://p.example/api", catalogName: w, audience: provider-data1, bucket: b}
            members: %s
            stacks:
              create: {auditBucket: audit, gitBucket: gitea}
              git: {domain: git.example.org}
              tre: {domains: {}}
            """.formatted(members), cloud));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theAdminsOfTheUsersFileAreNotMembers(AfonCloud cloud) throws IOException {
        AfonUserProvider users = new ProjectUserProvider(admins(), project(cloud, "[bob@example.org]"));
        assertThat(users.getAdmins()).extracting(AfonUser::username).containsExactly("root", "ada", "grace@example.org");
        assertThat(users.getAdmins()).allMatch(user -> user.has(Role.ADMIN) && !user.has(Role.MEMBER));
        assertThat(users.getAdmins()).extracting(AfonUser::password).containsOnly("p5ssw0rd");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void theMembersOfTheProjectAreNotAdmins(AfonCloud cloud) throws IOException {
        AfonUserProvider users = new ProjectUserProvider(admins(), project(cloud, "[{email: Bob@Example.org, firstName: Bob, lastName: Brown}, cy@example.org]"));
        assertThat(users.getMembers()).extracting(AfonUser::username).containsExactly("bob@example.org", "cy@example.org");
        assertThat(users.getMembers()).allMatch(user -> user.has(Role.MEMBER) && !user.has(Role.ADMIN) && user.password() == null);
        AfonUser bob = users.getUser("bob@example.org");
        assertThat(bob.email()).isEqualTo("bob@example.org");
        assertThat(bob.firstName()).isEqualTo("Bob");
        assertThat(bob.lastName()).isEqualTo("Brown");
        assertThat(users.getUser("cy@example.org").firstName()).isEqualTo(AfonUser.UNDEFINED);
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void aMemberWhoIsAnAdminByTheSameEmailIsBoth(AfonCloud cloud) throws IOException {
        AfonUserProvider users = new ProjectUserProvider(admins(), project(cloud, "[grace@example.org, bob@example.org]"));
        assertThat(users.getUsers()).extracting(AfonUser::username).containsExactly("root", "ada", "grace@example.org", "bob@example.org");
        AfonUser grace = users.getUser("grace@example.org");
        assertThat(grace.roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.MEMBER);
        assertThat(grace.password()).isEqualTo("p5ssw0rd");
        assertThat(users.getMembers()).extracting(AfonUser::username).containsExactly("grace@example.org", "bob@example.org");
        assertThat(users.getAdmins()).extracting(AfonUser::username).containsExactly("root", "ada", "grace@example.org");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void refusesAMemberWhoIsAnAdminWhoseUsernameIsNotTheirEmail(AfonCloud cloud) throws IOException {
        AfonUserProvider users = new ProjectUserProvider(admins(), project(cloud, "[ada@example.org]"));
        assertThatThrownBy(users::getUsers).isInstanceOf(IllegalStateException.class)
                                           .hasMessageContaining("user ada of users.csv").hasMessageContaining("ada@example.org")
                                           .hasMessageContaining("as their username");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void aMembersSlugIsALabelThatItsEmailAloneGives(AfonCloud cloud) throws IOException {
        AfonUserProvider users = new ProjectUserProvider(admins(),
            project(cloud, "[a.b@example.org, a-b@example.org, \"first+last.with-a-very-long-name-that-goes-on-and-on@example.org\"]"));
        // the users are made, so each slug passes the label check of the user
        assertThat(users.getMembers()).extracting(AfonUser::slug).doesNotHaveDuplicates().allMatch(slug -> slug.length() <= 63);
        assertThat(users.getUser("a.b@example.org").slug()).startsWith("a-b-");
        assertThat(ProjectUserProvider.slug("a.b@example.org")).isEqualTo(ProjectUserProvider.slug("a.b@example.org"));
        assertThat(ProjectUserProvider.slug("a.b@example.org")).isNotEqualTo(ProjectUserProvider.slug("a-b@example.org"));
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void membersAreAuthorisedOnNoCatalog(AfonCloud cloud) throws IOException {
        AfonUserProvider users = new ProjectUserProvider(admins(), project(cloud, "[bob@example.org]"));
        assertThat(users.isAuthorised("bob@example.org", "any")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void anUnknownUserIsNotFound(AfonCloud cloud) throws IOException {
        AfonUserProvider users = new ProjectUserProvider(admins(), project(cloud, "[bob@example.org]"));
        assertThatThrownBy(() -> users.getUser("nobody")).hasMessageContaining("nobody");
    }

    @ParameterizedTest
    @EnumSource(AfonCloud.class)
    void aUserOfTheUsersFileIsAdminAndMemberOutsideAProject(AfonCloud cloud) throws IOException {
        AfonUserProvider users = admins();
        assertThat(users.getAdmins()).hasSize(3);
        assertThat(users.getMembers()).hasSize(3);
    }
}
