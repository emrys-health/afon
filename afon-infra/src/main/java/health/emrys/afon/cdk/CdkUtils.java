package health.emrys.afon.cdk;

import com.hashicorp.cdktf.LocalExecProvisioner;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

public class CdkUtils {

    static @NotNull List<LocalExecProvisioner> uponDestroyRun(String... commands) {
        return Arrays.stream(commands).map(command ->
                                                   LocalExecProvisioner
                                                           .builder()
                                                           .type("local-exec")
                                                           .when("destroy")
                                                           .command(addingOrClauseIfNecessary(command))
                                                           .build()
                                          ).toList();
    }

    static @NotNull List<LocalExecProvisioner> uponCreateRun(String... commands) {
        return Arrays.stream(commands).map(command ->
                                                   LocalExecProvisioner
                                                           .builder()
                                                           .type("local-exec")
                                                           .when("create")
                                                           .command(command)
                                                           .build()
                                          ).toList();
    }

    private static final String addingOrClauseIfNecessary(String command) {
        if (!(command.endsWith("\n") || command.endsWith("\r"))) {
            return command + " || echo FAILED";
        }
        return command;
    }

}
