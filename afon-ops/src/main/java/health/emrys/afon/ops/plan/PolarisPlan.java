package health.emrys.afon.ops.plan;

import health.emrys.afon.cdk.components.PolarisComponent;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.Set;

import static health.emrys.afon.ops.plan.ComponentPlan.Endpoint.KEYCLOAK;
import static health.emrys.afon.ops.plan.ComponentPlan.Endpoint.POLARIS;

/**
 * The {@link ComponentPlan} for the {@link PolarisComponent}, decorating its deployment:
 * it holds the Polaris domain name of each cloud, which only one running stack can serve, needs its ACM certificate on AWS,
 * is waited for with KeyCloak once deployed, and has the OIDC users, catalogs and Polaris users configured
 * when the stack is set up.
 */
public class PolarisPlan implements ComponentPlan {

    @Override
    public String label() {
        return "Polaris";
    }

    @Override
    public List<AfonProperty> domains(AfonCloud cloud) {
        return List.of(AfonProperty.forCloud(cloud, "POLARIS_DOMAIN_NAME").orElseThrow());
    }

    @Override
    public List<AfonProperty> certificates(AfonCloud cloud) {
        return ComponentPlan.ofCloud(cloud, "POLARIS_CERT_ARN");
    }

    /**
     * Polaris does OIDC discovery against KeyCloak, its own or another stack's, so waits for it too.
     */
    @Override
    public Set<Endpoint> waitsFor() {
        return Set.of(POLARIS, KEYCLOAK);
    }

    /**
     * The OIDC users must be configured before the catalogs are created, as catalog creation authenticates as a client that
     * only the first step creates, and the catalogs before the users are configured on them.
     */
    @Override
    public List<Step> setupSteps(StepOperations ops, AfonCloud cloud) {
        return List.of(ops.configureOidcUsersFor(cloud),
                       ops.createAllCatalogsFor(cloud),
                       ops.configurePolarisUsersOnAllCatalogsFor(cloud));
    }
}
