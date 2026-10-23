package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.KeycloakComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.Set;

import static health.emrys.afon.ops.plan.ComponentPlan.Endpoint.KEYCLOAK;

/**
 * The {@link ComponentPlan} for the {@link KeycloakComponent}, decorating its deployment:
 * it holds the Keycloak domain name of the cloud ({@code <CLOUD>_OIDC_DOMAIN_NAME}, else {@code OIDC_DOMAIN_NAME}), which only one running stack can serve,
 * needs its ACM certificate on AWS, is waited for once deployed, and has its OIDC users configured when the stack is set up.
 * A stack without it uses the KeyCloak of another, for which it checks the answers.
 */
public class KeycloakPlan implements ComponentPlan {

    @Override
    public String label() {
        return "Keycloak";
    }

    @Override
    public List<AfonProperty> domains(AfonCloud cloud, AfonPropertyProvider properties) {
        return List.of(properties.getOidcDomainProperty(cloud));
    }

    @Override
    public Set<Endpoint> waitsFor() {
        return Set.of(KEYCLOAK);
    }

    @Override
    public List<Step> setupSteps(StepOperations ops, AfonCloud cloud) {
        return List.of(ops.configureOidcUsersFor(cloud));
    }
}
