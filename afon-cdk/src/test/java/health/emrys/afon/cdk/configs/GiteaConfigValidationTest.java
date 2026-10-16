package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.AfonUser;
import health.emrys.afon.config.AfonUserAcl;
import health.emrys.afon.config.AfonUserProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What would only fail when Gitea starts, after the release has timed out, fails here, so when the stack is validated.
 */
class GiteaConfigValidationTest {

    private static final String SECRET = "s3cretpass";

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    private GiteaConfig configOf(AfonCloud cloud, AfonUser... users) {
        properties.setValue(AfonProperty.GITEA_ADMIN_PASSWORD, "adm1n-passw0rd");
        properties.setValue(AfonProperty.AWS_GITEA_BUCKET, "bucket");
        properties.setValue(AfonProperty.AZURE_GITEA_BUCKET, "container");
        properties.setValue(AfonProperty.AZURE_CONNECTION_STRING, "AccountName=acct;AccountKey=key==");
        AfonUserProvider provider = new AfonUserProvider() {
            @Override public List<AfonUser> getUsers() { return List.of(users); }
            @Override public List<AfonUserAcl> getUserAcls() { return List.of(); }
            @Override public List<String> getUserNames() { return List.of(); }
            @Override public AfonUser getUser(String username) { return null; }
            @Override public boolean isAuthorised(String username, String catalogName) { return false; }
        };
        return new GiteaConfig(properties, provider, cloud, "full-x-cluster");
    }

    private static AfonUser user(String name, String password) {
        return AfonUserProvider.createUser(name, password);
    }

    @Test
    void acceptsPasswordsOfTheMinimumLength() {
        GiteaConfig config = configOf(AfonCloud.AWS, user("mauro", "12345678"));
        assertThat(config.usersList()).startsWith("mauro,").contains("12345678");
    }

    @Test
    void rejectsAUserWithAPasswordThatIsTooShortWithoutRevealingIt() {
        GiteaConfig config = configOf(AfonCloud.AWS, user("mauro", "12345678"), user("phil", SECRET.substring(0, 5)));
        assertThatThrownBy(config::usersList)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The password of user phil of users.csv is not set or shorter than 8 characters, which Gitea requires")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET.substring(0, 5)));
    }

    @Test
    void rejectsAUserWithoutAPassword() {
        GiteaConfig config = configOf(AfonCloud.AWS, user("will", null));
        assertThatThrownBy(config::usersList).hasMessageContaining("user will");
    }

    @Test
    void rejectsAnAdminPasswordThatIsBlankOrTooShort() {
        GiteaConfig config = configOf(AfonCloud.AWS);
        properties.setValue(AfonProperty.GITEA_ADMIN_PASSWORD, "");
        assertThatThrownBy(config::secretData).hasMessageContaining("GITEA_ADMIN_PASSWORD");
        properties.setValue(AfonProperty.GITEA_ADMIN_PASSWORD, "short");
        assertThatThrownBy(config::secretData).hasMessageContaining("the Gitea admin");
    }

    @Test
    void rejectsABlankBucketNamingItsProperty() {
        GiteaConfig aws = configOf(AfonCloud.AWS);
        properties.setValue(AfonProperty.AWS_GITEA_BUCKET, " ");
        assertThatThrownBy(aws::storageSection)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("AWS_GITEA_BUCKET is not set");

        GiteaConfig azure = configOf(AfonCloud.AZURE);
        properties.setValue(AfonProperty.AZURE_GITEA_BUCKET, "");
        assertThatThrownBy(azure::storageSection).hasMessageStartingWith("AZURE_GITEA_BUCKET is not set");
    }

    @Test
    void acceptsABucket() {
        assertThat(configOf(AfonCloud.AZURE).storageSection()).containsEntry("AZURE_BLOB_CONTAINER", "container");
    }
}
