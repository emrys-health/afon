package health.emrys.afon.ops.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmokeMemberCredentialsTest {

    @TempDir
    Path dir;

    @Test
    void writesThemReadableByTheirOwnerOnlyAndReadsThemBack() throws Exception {
        Path secrets = dir.resolve("secrets");
        new SmokeMemberCredentials("ada@example.org", "pw", "TOTP").write(secrets);

        assertThat(SmokeMemberCredentials.read(secrets, "ada@example.org")).contains(new SmokeMemberCredentials("ada@example.org", "pw", "TOTP"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(secrets.resolve(SmokeMemberCredentials.FILE)))).isEqualTo("rw-------");
    }

    @Test
    void readsNoneWhenTheFileIsMissingOrNotWhole() throws Exception {
        assertThat(SmokeMemberCredentials.read(dir, "ada@example.org")).isEmpty();
        Files.writeString(dir.resolve(SmokeMemberCredentials.FILE), "# c\nada@example.org,only-a-password\nada@example.org,,\n");
        assertThat(SmokeMemberCredentials.read(dir, "ada@example.org")).isEmpty();
    }

    @Test
    void keepsAllTheSmokeMembersInOneFileAndReplacesTheOneThatItWritesAgain() throws Exception {
        new SmokeMemberCredentials("ada@example.org", "pw1", "T1").write(dir);
        new SmokeMemberCredentials("Grace@Example.org", "pw2", "T2").write(dir);
        new SmokeMemberCredentials("ada@example.org", "pw3", "T3").write(dir);

        assertThat(SmokeMemberCredentials.readAll(dir).values()).containsExactly(
            new SmokeMemberCredentials("ada@example.org", "pw3", "T3"), new SmokeMemberCredentials("grace@example.org", "pw2", "T2"));
        assertThat(SmokeMemberCredentials.read(dir, "GRACE@example.org")).isPresent();
    }

    @Test
    void refusesACredentialThatTheCsvCannotHold() {
        assertThatThrownBy(() -> new SmokeMemberCredentials("ada@example.org", "a,b", "T").write(dir)).isInstanceOf(java.io.IOException.class).hasMessageContaining("comma");
    }

    @Test
    void makesLongRandomPasswordsOfLettersAndDigitsAndDoesNotPrintThem() {
        assertThat(SmokeMemberCredentials.randomPassword()).matches("[A-Za-z0-9]{32}").isNotEqualTo(SmokeMemberCredentials.randomPassword());
        assertThat(new SmokeMemberCredentials("a@b", "pw", "TOTP").toString()).doesNotContain("pw", "TOTP");
    }
}
