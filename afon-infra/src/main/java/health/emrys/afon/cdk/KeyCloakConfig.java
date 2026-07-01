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

public class KeyCloakConfig extends Construct {

    public static final String ADMIN_USER     = "admin-user";
    public static final String ADMIN_PASSWORD = "admin-password";
    private final       String SECRET_NAME    = "kc_secrets";

    public KeyCloakConfig(
        @NotNull Construct scope,
        @NotNull String id,
        @NotNull Properties properties) {
        super(scope, id);
        String adminUser     = KEYCLOAK_ADMIN.getValueFrom(properties);
        String adminPassword = KEYCLOAK_ADMIN_PASSWORD.getValueFrom(properties);

        Secret kcSecrets = Secret.Builder
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

        List<String> args = List.of();
        DeploymentSpecTemplateSpecContainer proxyContainer = DeploymentSpecTemplateSpecContainer
            .builder()
            .name("oauth2-proxy")
            .image("quay.io/oauth2-proxy/oauth2-proxy:v7.6.0")
            .args(args)
            .env(List.of(
                envFromSecret("OAUTH2_PROXY_CLIENT_ID", SECRET_NAME, ADMIN_USER),
                envFromSecret("OAUTH2_PROXY_COOKIE_SECRET", SECRET_NAME, ADMIN_PASSWORD)
            ))
            .build();
    }
}
