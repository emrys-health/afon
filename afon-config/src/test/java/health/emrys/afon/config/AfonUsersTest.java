package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class AfonUsersTest {

    @Test
    public void testLoadAllUsers() {
        AfonUserProvider provider = new FileUserProvider();
        List<AfonUser> users = provider.getUsers();
        assertThat(users).hasSizeGreaterThan(0);
    }

    @Test
    public void testLoadAllUsersFailsOnMissingFile() {
        try {
            AfonUserProvider provider = new FileUserProvider(List.of("missing.csv"));
            provider.getUsers();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("missing.csv");
        }
    }

}