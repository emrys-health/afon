package health.emrys.afon.cdk;

import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.TFUtils.envFromSecret;
import static health.emrys.afon.config.AfonProperties.KEYCLOAK_ADMIN;
import static health.emrys.afon.config.AfonProperties.KEYCLOAK_ADMIN_PASSWORD;
import static health.emrys.security.keycloak.KeyCloakConfig.*;

public class KeyCloakDeploymentConfig extends Construct {

    public static final String        ADMIN_USER     = "admin-user";
    public static final String        ADMIN_PASSWORD = "admin-password";
    public static final String        APP_NAME       = "keycloak";
    private final       String        SECRET_NAME    = "kcsecrets";

    public KeyCloakDeploymentConfig(
        @NotNull Construct scope,
        @NotNull Properties properties,
        @NotNull CloudProvider cloudProvider) {
        super(scope, APP_NAME + "_scope");
        String adminUser     = KEYCLOAK_ADMIN.getValueFrom(properties);
        String adminPassword = KEYCLOAK_ADMIN_PASSWORD.getValueFrom(properties);

        Secret.Builder
            .create(scope, SECRET_NAME)
            .metadata(SecretMetadata
                          .builder()
                          .name(SECRET_NAME)
                          .namespace("default")
                          .build())
            .data(Map.of(
                ADMIN_USER, adminUser,
                ADMIN_PASSWORD, adminPassword
            ))
            .build();

        List<String> args = List.of("start");
        DeploymentSpecTemplateSpecContainer keyCloak = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(APP_NAME)
            .image(KEYCLOAK_IMAGE)
            .args(args)
            .env(List.of(
                envFromSecret(KC_BOOTSTRAP_ADMIN_USERNAME, SECRET_NAME, ADMIN_USER),
                envFromSecret(KC_BOOTSTRAP_ADMIN_PASSWORD, SECRET_NAME, ADMIN_PASSWORD)
            ))
            .build();
        DeploymentUtils.deploy(
            this,
            cloudProvider.getDependables(),
            Map.of("app", APP_NAME),
            List.of(keyCloak),
            List.of(),
            APP_NAME);
    }
}
