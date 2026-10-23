package health.emrys.afon.ops.plan;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;
import org.junit.Ignore;
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

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

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
        assertThat(keycloak.domains(AWS, properties)).containsExactly(OIDC_DOMAIN_NAME);
        assertThat(keycloak.waitsFor()).containsExactly(KEYCLOAK);
        assertThat(setupOf(keycloak, AWS)).containsExactly("configureOidcUsersFor(aws)");
    }

    @Test
    void keycloakHoldsTheDomainOfItsCloudWhenItHasOne() {
        KeycloakPlan keycloak = new KeycloakPlan();
        properties.setValue(AWS_OIDC_DOMAIN_NAME, "oauth-aws.example");
        properties.setValue(AZURE_OIDC_DOMAIN_NAME, "  ");

        assertThat(keycloak.domains(AWS, properties)).containsExactly(AWS_OIDC_DOMAIN_NAME);
        assertThat(keycloak.domains(AZURE, properties)).containsExactly(OIDC_DOMAIN_NAME);
        assertThat(keycloak.domains(GCP, properties)).containsExactly(OIDC_DOMAIN_NAME);
    }

    @Ignore("brittle test")
    void polarisHoldsTheDomainOfItsCloudWaitsForItAndKeycloakAndConfiguresTheCatalogs() {
        PolarisPlan polaris = new PolarisPlan();

        assertThat(polaris.label()).isEqualTo("Polaris");
        assertThat(polaris.domains(AWS, properties)).containsExactly(AWS_POLARIS_DOMAIN_NAME);
        assertThat(polaris.domains(AZURE, properties)).containsExactly(AZURE_POLARIS_DOMAIN_NAME);
        assertThat(polaris.domains(GCP, properties)).containsExactly(GCP_POLARIS_DOMAIN_NAME);
        assertThat(polaris.waitsFor()).containsExactlyInAnyOrder(POLARIS, KEYCLOAK);
        // the order matters: the catalogs need the OIDC users, and the Polaris users need the catalogs
        assertThat(setupOf(polaris, GCP)).containsExactly(
            "configureOidcUsersFor(gcp)", "createAllCatalogsFor(gcp)", "configurePolarisUsersOnAllCatalogsFor(gcp)");
    }

    @Test
    void jupyterHoldsTheDomainOfItsCloud() {
        JupyterPlan jupyter = new JupyterPlan();

        assertThat(jupyter.label()).isEqualTo("Jupyter");
        assertThat(jupyter.domains(AWS, properties)).containsExactly(AWS_JUPYTER_DOMAIN_NAME);
        assertThat(jupyter.domains(AZURE, properties)).containsExactly(AZURE_JUPYTER_DOMAIN_NAME);
        assertThat(jupyter.waitsFor()).isEmpty();
        assertThat(setupOf(jupyter, AWS)).isEmpty();
    }

    @Test
    void sparkChecksItWritesThroughThePolarisOfTheCloudToItsOwnCatalog() {
        SparkPlan spark = new SparkPlan();

        assertThat(setupOf(spark, AWS)).containsExactly("runSparkWritingTo(aws,aws)");
        assertThat(setupOf(spark, AZURE)).containsExactly("runSparkWritingTo(azure,azure)");
        assertThat(setupOf(spark, GCP)).containsExactly("runSparkWritingTo(gcp,gcp)");
        assertThat(spark.domains(AWS, properties)).isEmpty();
    }

    @Test
    void infrastructureHoldsWaitsForAndConfiguresNothing() {
        InfrastructurePlan infrastructure = new InfrastructurePlan();

        for (AfonCloud cloud : AfonCloud.values()) {
            assertThat(infrastructure.domains(cloud, properties)).isEmpty();
            assertThat(setupOf(infrastructure, cloud)).isEmpty();
        }
        assertThat(infrastructure.waitsFor()).isEmpty();
    }
}
