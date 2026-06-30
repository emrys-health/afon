package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.cdk.TFUtils.envFromSecret;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.security.keycloak.KeyCloakConfig.*;

/**
 * These environment variable, although inappropriate for Docker, get the app to start:
 * docker run  -it -p 127.0.0.1:8080:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
      -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
      -e KC_DB_URL=jdbc:postgresql://polaris-db.crsocia0o0bd.eu-west-2.rds.amazonaws.com:5432/keycloak \
      -e KC_DB_USERNAME=postgres \
      -e KC_DB_PASSWORD=3mry5af0n \
      -e KC_DB=postgres \
      -e KC_PROXY=edge \
      -e KC_HOSTNAME=localhost \
      -e KC_HOSTNAME_STRICT=true \
      -e KC_HOSTNAME_STRICT_BACKCHANNEL=false \
      -e KC_HTTP_ENABLED=true \
      quay.io/keycloak/keycloak:26.6.3 start
 */
public class KeyCloakDeploymentConfig extends Construct {

    public static final String ADMIN_USER     = "admin-user";
    public static final String ADMIN_PASSWORD = "admin-password";
    public static final String APP_NAME       = "keycloak";
    private final       String SECRET_NAME    = "kcsecrets";

    public KeyCloakDeploymentConfig(
        @NotNull Construct scope,
        @NotNull Properties properties,
        @NotNull CloudProvider cloudProvider) {
        super(scope, APP_NAME + "_scope");
        String adminUser     = KC_BOOTSTRAP_ADMIN_USERNAME.getValueFrom(properties);
        String adminPassword = KC_BOOTSTRAP_ADMIN_PASSWORD.getValueFrom(properties);
        String dbUrl         = KC_DB_URL.getValueFrom(properties);
        String dbUser        = KC_DB_USERNAME.getValueFrom(properties);
        String dbPassword    = KC_DB_PASSWORD.getValueFrom(properties);

        Secret kcSecrets = Secret.Builder
            .create(scope, SECRET_NAME)
            .metadata(SecretMetadata
                          .builder()
                          .name(SECRET_NAME)
                          .namespace("default")
                          .build())
            .data(Map.of(
                ADMIN_USER, adminUser,
                ADMIN_PASSWORD, adminPassword,
                KC_DB_URL.getPropertyName(), dbUrl,
                KC_DB_USERNAME.getPropertyName(), dbUser,
                KC_DB_PASSWORD.getPropertyName(), dbPassword
            ))
            .build();

        List<String> args = List.of("start");
        DeploymentSpecTemplateSpecContainer keyCloak = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(APP_NAME)
            .image(KEYCLOAK_IMAGE)
            .args(args)
            .env(List.of(
                envFromSecret(KC_BOOTSTRAP_ADMIN_USERNAME.getPropertyName(), SECRET_NAME, ADMIN_USER),
                envFromSecret(KC_BOOTSTRAP_ADMIN_PASSWORD.getPropertyName(), SECRET_NAME, ADMIN_PASSWORD),
                envFromSecret(KC_DB_URL.getPropertyName(), SECRET_NAME, KC_DB_URL.getPropertyName()),
                envFromSecret(KC_DB_USERNAME.getPropertyName(), SECRET_NAME, KC_DB_USERNAME.getPropertyName()),
                envFromSecret(KC_DB_PASSWORD.getPropertyName(), SECRET_NAME, KC_DB_PASSWORD.getPropertyName())
            ))
            .build();
        List<ITerraformDependable> dependants = new ArrayList(cloudProvider.getDependables());
        dependants.add(kcSecrets);
        DeploymentUtils.deploy(
            this,
            dependants,
            Map.of("app", APP_NAME),
            List.of(keyCloak),
            List.of(),
            APP_NAME);
    }
}
