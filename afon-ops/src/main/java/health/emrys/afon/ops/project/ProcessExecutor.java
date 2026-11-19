package health.emrys.afon.ops.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs the Afon commands of a project in processes of their own, with the console as theirs.
 * <p>
 * A command of a project's home, which is given {@code --home}, must not switch the config or cdktf of that home to an env that the deployer's
 * own shell names, as the launcher would on seeing {@code AFON_ENV}, {@code AFON_CONFIG_ENV} or {@code AFON_CDKTF_ENV}, so those are not passed on.
 * Nor may it act on the cluster that the deployer's shell points kubectl at, so such a command has {@code KUBECONFIG} of the project's own cluster, if it has one.
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
     * Points the kubectl of a command of a project's home at the cluster of the project: {@code KUBECONFIG} is the kubeconfig that the project's stack wrote in
     * its cdktf dir, whatever the deployer's shell has, as the scripts that use kubectl act on the cluster that it points at, which would otherwise be the
     * deployer's, eg another project's.  Left as it is if the command is not of a project's home, or the project has no kubeconfig yet.
     */
    static void withKubeconfigOfTheProject(ProcessBuilder builder, List<String> command) {
        Path kubeconfig = command.contains("--home") ? kubeconfigOf(command) : null;
        if (kubeconfig != null) {
            builder.environment().put("KUBECONFIG", kubeconfig.toString());
        }
    }

    /**
     * @return the kubeconfig of the project whose cdktf dir the command is given, {@code <cdktf>/kube/<stack>.config}: the only one, or else the one that
     * {@code current.config} links to, which the last write of one points it at; {@code null} if there is none
     */
    static Path kubeconfigOf(List<String> command) {
        int at = command.indexOf("--cdktf");
        if (at < 0 || at + 1 >= command.size()) {
            return null;
        }
        Path kube = Path.of(command.get(at + 1), "kube");
        if (!Files.isDirectory(kube)) {
            return null;
        }
        try (Stream<Path> files = Files.list(kube)) {
            List<Path> configs = files.filter(file -> file.getFileName().toString().endsWith(".config") && !file.getFileName().toString().equals("current.config"))
                                      .sorted().toList();
            if (configs.size() == 1) {
                return configs.get(0);
            }
        } catch (IOException e) {
            return null;
        }
        Path current = kube.resolve("current.config");
        return Files.isRegularFile(current) ? current : null;
    }

    /**
     * @param extraEnv more env for the process, eg the cloud of the project
     */
    @Override
    public int run(List<String> command, Map<String, String> extraEnv) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        withoutEnvSwitches(builder, command);
        withKubeconfigOfTheProject(builder, command);
        builder.environment().putAll(extraEnv);
        try {
            return builder.start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + command.get(0), e);
        }
    }
}
