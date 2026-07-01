package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import health.emrys.afon.config.AfonProperty;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.TFUtils.envFromSecret;
import static health.emrys.afon.cdk.TFUtils.envVar;
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

    public static final String APP_NAME       = "keycloak";
    private final       String SECRET_NAME    = "kcsecrets";

    public KeyCloakDeploymentConfig(
        @NotNull Construct scope,
        @NotNull Properties properties,
        @NotNull CloudProvider cloudProvider) {
        super(scope, APP_NAME + "_scope");

        Set<AfonProperty> kcProperties = Set.of(
            KC_BOOTSTRAP_ADMIN_USERNAME,
            KC_BOOTSTRAP_ADMIN_PASSWORD,
            KC_DB_URL,
            KC_DB_USERNAME,
            KC_DB_PASSWORD);

        Map<String, String> secretsMap = kcProperties.stream().collect(Collectors.toMap(
            AfonProperty::getPropertyName,
            x -> x.getValueFrom(properties)
        ));

        Secret kcSecrets = Secret.Builder
            .create(scope, SECRET_NAME)
            .metadata(SecretMetadata
                          .builder()
                          .name(SECRET_NAME)
                          .namespace("default")
                          .build())
            .data(secretsMap)
            .build();

        List<DeploymentSpecTemplateSpecContainerEnv> envs = new ArrayList<>();
        envs.add(envVar(KC_DB.getPropertyName(), KC_DB.getValueFrom(properties)));
        envs.add(envVar("KC_PROXY", "edge"));
        envs.add(envVar("KC_HOSTNAME_STRICT", "true"));
        envs.add(envVar("KC_HOSTNAME_STRICT_BACKCHANNE", "false"));
        envs.add(envVar("KC_HTTP_ENABLED", "true"));
        envs.add(envVar(KC_HOSTNAME.getPropertyName(), KC_HOSTNAME.getValueFrom(properties)));
        kcProperties.stream().forEach(x -> envs.add(
            envFromSecret(x.getPropertyName(), SECRET_NAME, x.getPropertyName()))
        );

        List<String> args = List.of("start");
        DeploymentSpecTemplateSpecContainer keyCloak = DeploymentSpecTemplateSpecContainer
            .builder()
            .name(APP_NAME)
            .image(KEYCLOAK_IMAGE)
            .args(args)
            .env(envs)
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
