package health.emrys.afon.cdk;

import com.hashicorp.cdktf.Testing;
import com.hashicorp.cdktf.TerraformStack;
import com.hashicorp.cdktf.providers.kubernetes.service.ServiceSpecPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CdkHelperServiceTest {

    private static final List<ServiceSpecPort> PORTS = List.of(ServiceSpecPort.builder().port(8181).build());

    private String synthService(Map<String, String> annotations) {
        var stack = new TerraformStack(Testing.app(), "test");
        CdkHelper.clusterIpService(List.of(), Map.of("app", "x"), "svc", PORTS, stack, annotations);
        return Testing.synth(stack);
    }

    @Test
    void serviceHasTheAnnotationsGiven() {
        assertThat(synthService(Map.of("cloud.google.com/neg", "{\"ingress\": false}")))
            .contains("cloud.google.com/neg");
    }

    @Test
    void serviceHasNoAnnotationsByDefault() {
        assertThat(synthService(Map.of())).doesNotContain("annotations");
    }

}
