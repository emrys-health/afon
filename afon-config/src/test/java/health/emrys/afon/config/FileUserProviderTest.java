package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static health.emrys.afon.config.FilePropertyProviderTest.TEST_CONFIG_DIR;
import static org.assertj.core.api.Assertions.assertThat;

public class FileUserProviderTest {

    static String CONFIG_DIR = "../templates/config";

    @Test
    public void canLoadAllUsersAndAcls() {
        AfonUserProvider provider = new FileUserProvider(CONFIG_DIR);
        assertThat(provider.getUsers()).hasSize(2);
        assertThat(provider.getUserAcls()).hasSize(2);
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
            AfonUserProvider provider = new FileUserProvider(CONFIG_DIR, List.of("missing-users.csv"), List.of());
            provider.getUsers();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("missing-users.csv");
        }
    }

    @Test
    public void canLoadAllUsersWithoutAclFile() {
        AfonUserProvider provider = new FileUserProvider(CONFIG_DIR, List.of("users.csv"), List.of("missing-acl.csv"));
        assertThat(provider.getUsers()).isNotEmpty();
        assertThat(provider.getUserAcls()).isEmpty();
    }

    @Test
    public void canAuthenticateUsersWithAclFile() {
        AfonUserProvider provider = new FileUserProvider(CONFIG_DIR);
        assertThat(provider.isAuthorised("mauro", "azure")).isTrue();
        assertThat(provider.isAuthorised("mauro", "azure_external")).isTrue();
        assertThat(provider.isAuthorised("mauro", "any_other")).isFalse();
        assertThat(provider.isAuthorised("phil", "any")).isTrue();
    }

    @Test
    public void canDenyAllUsersWithoutAclFile() {
        AfonUserProvider provider = new FileUserProvider(CONFIG_DIR, List.of("users.csv"), List.of("missing-acl.csv"));
        assertThat(provider.getUserAcls()).isEmpty();
        assertThat(provider.isAuthorised("mauro", "any")).isFalse();
        assertThat(provider.isAuthorised("phil", "any")).isFalse();
    }

}