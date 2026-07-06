package health.emrys.afon.platform;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.demo.StepRunner;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The single config-driven entry point:
 * <pre>
 * mvn compile exec:java \
 *     -Dexec.mainClass=health.emrys.afon.platform.DeployFromConfigMain \
 *     -Dexec.args="docs/examples/emrys-demo.yaml [firstStepToRun]"
 * </pre>
 * Parses and validates the YAML, plans it (see {@link DeploymentPlanner}) and
 * runs the steps with the existing {@code StepRunner}, so resume-from-step
 * works exactly as it does for the legacy {@code Deploy*Main} classes.
 */
public class DeployFromConfigMain {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DeployFromConfigMain.class);

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            log.error("Usage: DeployFromConfigMain <afon-deployment.yaml> [firstStepToRun]");
            return;
        }
        DeploymentPlan plan = new DeploymentPlanner()
            .plan(Path.of(args[0]), new AfonPropertiesLoader().getProperties());
        log.info("Planned deployment:\n{}", plan.describe());

        StepRunner runner = new StepRunner();
        if (args.length > 1) {
            runner.runFrom(plan.steps(), args[1]);
        } else {
            runner.runSteps(plan.steps());
        }
    }
}
