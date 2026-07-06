package health.emrys.afon.platform;

import health.emrys.afon.config.AfonPropertiesLoader;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Renders the plan for an {@code afon-deployment.yaml} as markdown without
 * executing anything — the config-driven counterpart of the
 * {@code Document*Main} classes, keeping docs in lock-step with the plan.
 */
public class DocumentFromConfigMain {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: DocumentFromConfigMain <afon-deployment.yaml>");
            return;
        }
        DeploymentPlan plan = new DeploymentPlanner()
            .plan(Path.of(args[0]), new AfonPropertiesLoader().getProperties());
        System.out.println(plan.describe());
    }
}
