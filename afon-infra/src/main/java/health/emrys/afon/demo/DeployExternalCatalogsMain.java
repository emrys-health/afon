package health.emrys.afon.demo;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AwsWholeStack;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import org.apache.commons.io.file.PathUtils;

public class DeployExternalCatalogsMain {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DeployExternalCatalogsMain.class);

    static interface ThrowableRunnable {
        public void run() throws Exception;
    }

    static class Step {
        private final String   name;
        private final ThrowableRunnable function;

        Step(String name, ThrowableRunnable function) {
            this.name     = name;
            this.function = function;
        }
    }

    private static final List<Step> steps = List.of(
        new Step("aws_deploy", () -> {
            Path toDelete = Path.of("cdktf.out", "stacks", AwsWholeStack.ID);
            log.info("Deleting {}", toDelete);
            PathUtils.delete(toDelete);
            log.info("Creating terraform file");
            AwsWholeStack.main(new String[]{});

            tofuIn(toDelete, "init");
            tofuIn(toDelete, "apply", "--auto-approve");
        })
                                            );

    private static void tofuIn(Path path, String... commands) throws ManagedProcessException {
        ManagedProcessBuilder pb = new ManagedProcessBuilder("tofu");
        for (String command : commands) {
            pb.addArgument(command);
        }
        pb.setWorkingDirectory(path.toFile())
            .setDestroyOnShutdown(true)
            .addStdOut(System.out)
            .addStdErr(System.err)
            .setConsoleBufferMaxLines(7000);
        ManagedProcess p = pb.build();
        p.start();
        p.isAlive();
        p.waitForExit();
        log.info("Command {} finished with value {}", "tofu", p.exitValue());
    }

    public static void main(String[] args) {
        DeployExternalCatalogsMain app = new DeployExternalCatalogsMain();
        try {
            app.runSteps(steps);
        } catch (Exception e) {
            log.error("FAILED: " +e.getMessage(), e);
        }
    }

    private void runSteps(List<Step> steps) throws Exception {
        for (Step step: steps) {
            log.info("Running step {}", step.name);
            step.function.run();
        }
    }

}
