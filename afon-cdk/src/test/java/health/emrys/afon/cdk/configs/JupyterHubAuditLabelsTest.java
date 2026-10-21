package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit labels are stamped only for the AUDIT_* properties set, so that a TRE without an auditor spawns its pods as before.
 */
class JupyterHubAuditLabelsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The hub's jupyterhub_config.py, in the plan of the full AWS stack. */
    private static String hubConfigOf(Path dir, Map<String, String> overrides) throws Exception {
        String plan = SynthesisSupport.synthesise(StackId.parse("aws-full"), dir, overrides);
        return JSON.readTree(plan).path("resource").path("kubernetes_config_map").path("jupyterhubConfigMap")
                   .path("data").path("jupyterhub_config.py").asText();
    }

    @Test
    void stampsNoAuditLabelWhereNoneIsConfigured(@TempDir Path dir) throws Exception {
        assertThat(hubConfigOf(dir, Map.of())).contains("_AUDIT_LABELS = {}\n");
    }

    @Test
    void stampsTheAuditLabelsConfigured(@TempDir Path dir) throws Exception {
        String config = hubConfigOf(dir, Map.of(
            "AUDIT_AE_ID", "ae-1",
            "AUDIT_PROJECT_ID", "project-1",
            "AUDIT_COMMISSIONING_ORGANIZATION_ID", "org-1",
            "AUDIT_TRE_ID", "tre-1"));

        assertThat(config).contains(
            "_AUDIT_LABELS = {'audit/ae-id': 'ae-1', 'audit/commissioning-organization-id': 'org-1', "
            + "'audit/project-id': 'project-1', 'audit/tre-id': 'tre-1'}\n");
    }

    @Test
    void leavesOutTheAuditLabelsNotConfigured(@TempDir Path dir) throws Exception {
        assertThat(hubConfigOf(dir, Map.of("AUDIT_TRE_ID", "tre-1")))
            .contains("_AUDIT_LABELS = {'audit/tre-id': 'tre-1'}\n");
    }
}
