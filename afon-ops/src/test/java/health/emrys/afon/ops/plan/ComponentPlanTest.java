package health.emrys.afon.ops.plan;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.ops.plan.ComponentPlan.Endpoint.KEYCLOAK;
import static health.emrys.afon.ops.plan.ComponentPlan.Endpoint.POLARIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

class ComponentPlanTest {

    /**
     * Creates each step named after the call that created it.
     */
    private final StepOperations ops = mock(StepOperations.class, (Answer<Object>) invocation -> {
        if (invocation.getMethod().getReturnType() != Step.class) {
            return RETURNS_DEFAULTS.answer(invocation);
        }
        String call = String.format("%s(%s)", invocation.getMethod().getName(),
            Arrays.stream(invocation.getArguments()).map(String::valueOf).collect(Collectors.joining(",")));
        return new Step(call, call, () -> {});
    });

    private List<String> setupOf(ComponentPlan plan, AfonCloud cloud) {
        return plan.setupSteps(ops, cloud).stream().map(Step::getName).toList();
    }

    @Test
    void keycloakHoldsItsDomainWaitsForItAndConfiguresTheOidcUsers() {
        KeycloakPlan keycloak = new KeycloakPlan();

        assertThat(keycloak.label()).isEqualTo("Keycloak");
        assertThat(keycloak.domains(AWS)).containsExactly(OIDC_DOMAIN_NAME);
        assertThat(keycloak.waitsFor()).containsExactly(KEYCLOAK);
        assertThat(setupOf(keycloak, AWS)).containsExactly("configureOidcUsersFor(aws)");
        // only AWS has certificates for its load balancers
        assertThat(keycloak.certificates(AWS)).containsExactly(AWS_KEYCLOAK_CERT_ARN);
        assertThat(keycloak.certificates(AZURE)).isEmpty();
        assertThat(keycloak.certificates(GCP)).isEmpty();
    }

    @Test
    void polarisHoldsTheDomainOfItsCloudWaitsForItAndKeycloakAndConfiguresTheCatalogs() {
        PolarisPlan polaris = new PolarisPlan();

        assertThat(polaris.label()).isEqualTo("Polaris");
        assertThat(polaris.domains(AWS)).containsExactly(AWS_POLARIS_DOMAIN_NAME);
        assertThat(polaris.domains(AZURE)).containsExactly(AZURE_POLARIS_DOMAIN_NAME);
        assertThat(polaris.domains(GCP)).containsExactly(GCP_POLARIS_DOMAIN_NAME);
        assertThat(polaris.waitsFor()).containsExactlyInAnyOrder(POLARIS, KEYCLOAK);
        // the order matters: the catalogs need the OIDC users, and the Polaris users need the catalogs
        assertThat(setupOf(polaris, GCP)).containsExactly(
            "configureOidcUsersFor(gcp)", "createAllCatalogsFor(gcp)", "configurePolarisUsersOnAllCatalogsFor(gcp)");
        assertThat(polaris.certificates(AWS)).containsExactly(AWS_POLARIS_CERT_ARN);
        assertThat(polaris.certificates(AZURE)).isEmpty();
    }

    @Test
    void jupyterHoldsTheDomainOfItsCloudAndNeedsItsCertificateOnAws() {
        JupyterPlan jupyter = new JupyterPlan();

        assertThat(jupyter.label()).isEqualTo("Jupyter");
        assertThat(jupyter.domains(AWS)).containsExactly(AWS_JUPYTER_DOMAIN_NAME);
        assertThat(jupyter.domains(AZURE)).containsExactly(AZURE_JUPYTER_DOMAIN_NAME);
        assertThat(jupyter.certificates(AWS)).containsExactly(AWS_JUPYTER_CERT_ARN);
        assertThat(jupyter.waitsFor()).isEmpty();
        assertThat(setupOf(jupyter, AWS)).isEmpty();
    }

    @Test
    void sparkChecksItWritesThroughPolarisOnAwsOnly() {
        SparkPlan spark = new SparkPlan();

        assertThat(setupOf(spark, AWS)).containsExactly("runAwsSparkWritingTo(aws)");
        assertThat(setupOf(spark, AZURE)).isEmpty();
        assertThat(setupOf(spark, GCP)).isEmpty();
        assertThat(spark.domains(AWS)).isEmpty();
    }

    @Test
    void infrastructureHoldsWaitsForAndConfiguresNothing() {
        InfrastructurePlan infrastructure = new InfrastructurePlan();

        for (AfonCloud cloud : AfonCloud.values()) {
            assertThat(infrastructure.domains(cloud)).isEmpty();
            assertThat(infrastructure.certificates(cloud)).isEmpty();
            assertThat(setupOf(infrastructure, cloud)).isEmpty();
        }
        assertThat(infrastructure.waitsFor()).isEmpty();
    }
}
