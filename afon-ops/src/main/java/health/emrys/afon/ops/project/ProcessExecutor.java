package health.emrys.afon.ops.project;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Runs the Afon commands of a project in processes of their own, with the console as theirs.
 * <p>
 * A command of a project's home, which is given {@code --home}, must not switch the config or cdktf of that home to an env that the deployer's
 * own shell names, as the launcher would on seeing {@code AFON_ENV}, {@code AFON_CONFIG_ENV} or {@code AFON_CDKTF_ENV}, so those are not passed on.
 */
final class ProcessExecutor implements ManageProjectMain.Executor {

    private static final List<String> ENV_SWITCHES = List.of("AFON_ENV", "AFON_CONFIG_ENV", "AFON_CDKTF_ENV");

    @Override
    public int run(List<String> command) throws IOException {
        return run(command, Map.of());
    }

    /**
     * Takes the env switches out of the env of the process, if the command is of a project's home.
     */
    static void withoutEnvSwitches(ProcessBuilder builder, List<String> command) {
        if (command.contains("--home")) {
            builder.environment().keySet().removeAll(ENV_SWITCHES);
        }
    }

    /**
     * @param extraEnv more env for the process, eg the cloud of the project
     */
    @Override
    public int run(List<String> command, Map<String, String> extraEnv) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        withoutEnvSwitches(builder, command);
        builder.environment().putAll(extraEnv);
        try {
            return builder.start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + command.get(0), e);
        }
    }
}
