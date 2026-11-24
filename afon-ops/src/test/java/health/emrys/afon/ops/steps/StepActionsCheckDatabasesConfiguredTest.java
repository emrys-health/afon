package health.emrys.afon.ops.steps;

import health.emrys.afon.ops.plan.ComponentPlan.Database;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepActionsCheckDatabasesConfiguredTest {

    private final StepActions actions = new StepActions();

    @Test
    void passesWhenEveryDatabaseHasAHost() {
        // without connecting to them: nothing listens at these
        assertThatCode(() -> actions.checkDatabasesConfigured(List.of(
            new Database("Polaris", "POLARIS_DB_HOST", "polaris-db.invalid"),
            new Database("Gitea", "GITEA_DB_HOST", "gitea-db.invalid")))).doesNotThrowAnyException();
    }

    @Test
    void failsNamingEveryDatabaseWithoutAHost() {
        assertThatThrownBy(() -> actions.checkDatabasesConfigured(List.of(
            new Database("Polaris", "POLARIS_DB_HOST", ""),
            new Database("Keycloak", "KEYCLOAK_DB_URL", "kc-db.invalid"),
            new Database("Gitea", "GITEA_DB_HOST", " "))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not configured: set POLARIS_DB_HOST for the Polaris database and GITEA_DB_HOST for the Gitea database")
            .hasMessageNotContaining("Keycloak");
    }
}
