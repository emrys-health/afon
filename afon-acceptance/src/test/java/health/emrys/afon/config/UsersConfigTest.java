package health.emrys.afon.config;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class UsersConfigTest {

    public static final int MIN_PASSWORD_SIZE = 8;

    @Test
    public void passwordLengthsForGitea() {
        new FileUserProvider().getUsers().stream().forEach(u ->
            assertThat(u.password().length())
                .as(u.username() + " has a password of length >= " + MIN_PASSWORD_SIZE)
                .isGreaterThanOrEqualTo(MIN_PASSWORD_SIZE)
            );
    }

}
