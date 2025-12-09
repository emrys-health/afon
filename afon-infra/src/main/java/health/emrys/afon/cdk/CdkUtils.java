package health.emrys.afon.cdk;

import com.hashicorp.cdktf.LocalExecProvisioner;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CdkUtils {

    static @NotNull List<LocalExecProvisioner> uponDestroyRun(String command) {
        return List.of(LocalExecProvisioner
                               .builder()
                               .type("local-exec")
                               .when("destroy")
                               .command(command)
                               .build());
    }

}
