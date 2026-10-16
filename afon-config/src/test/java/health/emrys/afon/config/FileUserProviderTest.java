package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static health.emrys.afon.config.FilePropertyProviderTest.TEMPLATES_CONFIG_DIR;
import static health.emrys.afon.config.FilePropertyProviderTest.TEST_CONFIG_DIR;
import static org.assertj.core.api.Assertions.assertThat;

public class FileUserProviderTest {

    @Test
    public void canLoadAllUsersAndAcls() {
        AfonUserProvider provider = new FileUserProvider(TEMPLATES_CONFIG_DIR);
        assertThat(provider.getUsers()).hasSizeGreaterThan(0);
        assertThat(provider.getUserAcls()).hasSizeGreaterThan(0);
    }

    @Test
    public void cannotLoadUsersWithInvalidSlug() {
        try {
            AfonUserProvider provider = new FileUserProvider(TEST_CONFIG_DIR, List.of("users-invalid.csv"), List.of());
            provider.getUsers();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("slug 'phil@test.com' for user 'phil' is not RFC-1123 DNS compliant");
        }
    }

    @Test
    public void cannotLoadAllUsersOnMissingFile() {
        try {
            AfonUserProvider provider = new FileUserProvider(TEMPLATES_CONFIG_DIR, List.of("missing-users.csv"), List.of());
            provider.getUsers();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("missing-users.csv");
        }
    }

    @Test
    public void canLoadAllUsersWithoutAclFile() {
        AfonUserProvider provider = new FileUserProvider(TEMPLATES_CONFIG_DIR, List.of("users.csv"), List.of("missing-acl.csv"));
        assertThat(provider.getUsers()).isNotEmpty();
        assertThat(provider.getUserAcls()).isEmpty();
    }

    @Test
    public void canAuthenticateUsersWithAclFile() {
        AfonUserProvider provider = new FileUserProvider(TEMPLATES_CONFIG_DIR);
        assertThat(provider.isAuthorised("mauro", "any")).isTrue();
        assertThat(provider.isAuthorised("phil", "any")).isTrue();
        assertThat(provider.isAuthorised("will", "*_forbidden")).isFalse();
    }

    @Test
    public void canDenyAllUsersWithoutAclFile() {
        AfonUserProvider provider = new FileUserProvider(TEMPLATES_CONFIG_DIR, List.of("users.csv"), List.of("missing-acl.csv"));
        assertThat(provider.getUserAcls()).isEmpty();
        assertThat(provider.isAuthorised("mauro", "any")).isFalse();
        assertThat(provider.isAuthorised("phil", "any")).isFalse();
    }

    @Test
    public void canGetConfiguredUser() {
        AfonPropertyProvider properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
        properties.setValue(AfonProperty.USER_ID, "phil");
        AfonUserProvider provider = new FileUserProvider(TEMPLATES_CONFIG_DIR);
        AfonUser user = provider.getConfiguredUser(properties);
        assertThat(user.username()).isEqualTo("phil");
        assertThat(user.password()).isEqualTo("p5ssw0rd");
    }

}