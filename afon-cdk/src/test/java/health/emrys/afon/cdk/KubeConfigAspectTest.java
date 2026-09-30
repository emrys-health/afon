package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashicorp.cdktf.Aspects;
import com.hashicorp.cdktf.LocalExecProvisioner;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.Testing;
import com.hashicorp.cdktf.providers.null_provider.provider.NullProvider;
import com.hashicorp.cdktf.providers.null_provider.resource.Resource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static health.emrys.afon.cdk.CdkUtils.uponCreateRun;
import static health.emrys.afon.cdk.CdkUtils.uponDestroyRun;
import static org.assertj.core.api.Assertions.assertThat;

class KubeConfigAspectTest {

    private static final String KUBE_CONFIG_PATH = "/cdktf/kube/aws-keycloak.config";

    @Test
    void setsKubeConfigOnEveryLocalExecProvisioner() throws Exception {
        TerraformStack stack = new TerraformStack(Testing.app(), "aws-keycloak");
        NullProvider.Builder.create(stack, "nullProvider").build();
        List<LocalExecProvisioner> provisioners = new ArrayList<>(uponCreateRun("kubectl apply -f -"));
        provisioners.addAll(uponDestroyRun("kubectl delete crd x"));
        provisioners.add(LocalExecProvisioner.builder()
                                             .type("local-exec")
                                             .command("helm list")
                                             .environment(Map.of("OTHER", "value"))
                                             .build());
        Resource.Builder.create(stack, "withProvisioners").provisioners(new ArrayList<>(provisioners)).build();
        Resource.Builder.create(stack, "withoutProvisioners").build();

        Aspects.of(stack).add(new KubeConfigAspect(KUBE_CONFIG_PATH));

        JsonNode resources = new ObjectMapper().readTree(Testing.synth(stack)).path("resource").path("null_resource");
        JsonNode synthesised = resources.path("withProvisioners").path("provisioner");
        assertThat(synthesised).hasSize(3);
        for (JsonNode provisioner : synthesised) {
            assertThat(provisioner.path("local-exec").path("environment").path("KUBECONFIG").asText())
                .isEqualTo(KUBE_CONFIG_PATH);
        }
        JsonNode create = synthesised.get(0).path("local-exec");
        assertThat(create.path("command").asText()).isEqualTo("kubectl apply -f -");
        assertThat(create.path("when").asText()).isEqualTo("create");
        JsonNode destroy = synthesised.get(1).path("local-exec");
        assertThat(destroy.path("command").asText()).isEqualTo("kubectl delete crd x || echo FAILED");
        assertThat(destroy.path("when").asText()).isEqualTo("destroy");
        assertThat(synthesised.get(2).path("local-exec").path("environment").path("OTHER").asText()).isEqualTo("value");
        assertThat(resources.path("withoutProvisioners").has("provisioner")).isFalse();
    }

}
