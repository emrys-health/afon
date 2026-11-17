package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static health.emrys.afon.config.AfonUser.Role.ADMIN;
import static health.emrys.afon.config.AfonUser.Role.MEMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfonUserRolesTest {

    private static AfonUser user() {
        return AfonUserProvider.createUser("will", "p5ssw0rd", "will@example.org", "will", "Will", "B");
    }

    @Test
    void aUserOfTheUsersFileIsAnAdminAndAMember() {
        assertThat(user().roles()).containsExactlyInAnyOrder(ADMIN, MEMBER);
        assertThat(user().has(ADMIN)).isTrue();
        assertThat(user().has(MEMBER)).isTrue();
    }

    @Test
    void canBeGivenOnlyTheRolesItIsToHave() {
        AfonUser admin = user().withRoles(ADMIN);
        assertThat(admin.roles()).containsExactly(ADMIN);
        assertThat(admin.has(MEMBER)).isFalse();
        assertThat(admin.username()).isEqualTo("will");
    }

    @Test
    void hasAtLeastOneRole() {
        assertThatThrownBy(() -> new AfonUser("will", "p", "w@example.org", "will", "W", "B", Set.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no role");
    }

    @Test
    void cannotBeGivenNoRoles() {
        assertThatThrownBy(() -> user().withRoles()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no role");
    }

    @Test
    void hasRolesThatCannotBeChangedAfterwards() {
        assertThatThrownBy(() -> user().roles().remove(ADMIN)).isInstanceOf(UnsupportedOperationException.class);
    }
}
