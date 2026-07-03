package health.emrys.afon.cdk;

import com.hashicorp.cdktf.ITerraformDependable;
import com.hashicorp.cdktf.providers.kubernetes.deployment.Deployment;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainer;
import com.hashicorp.cdktf.providers.kubernetes.deployment.DeploymentSpecTemplateSpecContainerEnv;
import com.hashicorp.cdktf.providers.kubernetes.secret.Secret;
import com.hashicorp.cdktf.providers.kubernetes.secret.SecretMetadata;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import health.emrys.afon.config.AfonProperty;
import org.jetbrains.annotations.NotNull;
import software.constructs.Construct;

import java.util.*;
import java.util.stream.Collectors;

import static health.emrys.afon.cdk.TFUtils.*;
import static health.emrys.afon.config.AfonProperties.*;
import static health.emrys.security.keycloak.KeyCloakConfig.KEYCLOAK_IMAGE;
import static health.emrys.security.keycloak.KeyCloakConfig.KEYCLOAK_PORT;

/**
 * These environment variable, although inappropriate for Docker, get the app to start:
 * docker run  -it -p 127.0.0.1:8080:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
 * -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
 * -e KC_DB_URL=jdbc:postgresql://polaris-db.crsocia0o0bd.eu-west-2.rds.amazonaws.com:5432/keycloak \
 * -e KC_DB_USERNAME=postgres \
 * -e KC_DB_PASSWORD=3mry5af0n \
 * -e KC_DB=postgres \
 * -e KC_PROXY=edge \
 * -e KC_HOSTNAME=localhost \
 * -e KC_HOSTNAME_STRICT=true \
 * -e KC_HOSTNAME_STRICT_BACKCHANNEL=false \
 * -e KC_HTTP_ENABLED=true \
 * quay.io/keycloak/keycloak:26.6.3 start
 */
public abstract class KeyCloakDeploymentConfig<T extends CloudProvider> extends Construct {

    public static final String          APP_NAME         = "keycloak";
    public static final String          KC_SVC           = "keycloak-service";
    public static final ServiceSpecPort KC_PORT          = ServiceSpecPort.builder()
                                                                          .name("web")
                                                                          .port(KEYCLOAK_PORT)
                                                                          .targetPort("" + KEYCLOAK_PORT)
                                                                          .protocol("TCP")
                                                                          .build();
    private final       String          SECRET_NAME      = "kcsecrets";
    protected final     String          KEYCLOAK_INGRESS = "keycloak-ingress";
    protected final     T               cloudProvider;

    public KeyCloakDeploymentConfig(
        @NotNull Construct scope,
        @NotNull Properties properties,
        @NotNull T cloudProvider) {
        super(scope, APP_NAME + "_scope");
        this.cloudProvider = cloudProvider;

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
        Map<String, String> labels = Map.of("app", APP_NAME);
        Deployment app = DeploymentUtils.deploy(
            this,
            dependants,
            labels,
            List.of(keyCloak),
            List.of(),
            APP_NAME);

        dependants.add(app);
        // NodePort (not ClusterIP): the AWS Load Balancer Controller uses instance targets by
        // default and requires a node port to build the ingress TargetGroup.
        nodePortService(dependants, labels, KC_SVC, List.of(KC_PORT), this);
    }

    protected abstract void configureHttpsAndDNS();
}
