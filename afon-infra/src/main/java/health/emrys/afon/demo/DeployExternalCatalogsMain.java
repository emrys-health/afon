package health.emrys.afon.demo;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.demo.Steps.*;

/**
 * This sets the cloud environments up just as they were for the TDA demo of 23/3/26.
 * The main thing is:
 * - we have all teh catalogs in AWS plus the external one that talks to:
 * - an Azure Polaris with an Azure catalog
 * - a smoke test that ensures that Spark (running locally) can talk to the AWS catalog that is a proxy for the Azure one
 */
public class DeployExternalCatalogsMain {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DeployExternalCatalogsMain.class);

    private static final List<Step> steps(Steps steps) {
        return List.of(
            steps.PREPARE_TOFU(),
            steps.CLEAN_AWS(),
            steps.AWS_POLARIS(),
            steps.WAIT_FOR_AWS_POLARIS(),
            steps.AWS_SPARK(),
            steps.CLEAN_AZ(),
            steps.AZ_POLARIS(),
            steps.WAIT_FOR_AZ_POLARIS(),
            steps.AWS_ALL_CATALOGS(),
            steps.AZ_POLARIS_CLEAN_FOREIGN_CATALOG(),
            steps.AZ_POLARIS_FOREIGN_CATALOG(),
            steps.AZ_POLARIS_EXTERNAL_AWS(),
            steps.CHECK_AWS_TO_AZURE_EXTERNAL()
                );
    }

    public static void main(String[] args) {
        List<Step> steps = steps(new Steps(new StepActions()));
        log.info("Steps:\n{}", humanReadable(steps));
        DeployExternalCatalogsMain app = new DeployExternalCatalogsMain();
        try {
            if (args.length == 0) {
                app.runSteps(steps);
            } else {
                String step  = args[0];
                int    index = steps.stream().map(s -> s.name).toList().lastIndexOf(step);
                if (index == -1) {
                    log.error("Step {} not found", step);
                } else {
                    app.runSteps(steps.subList(index, steps.size()));
                }
            }
        } catch (Exception e) {
            log.error("FAILED: " + e.getMessage(), e);
        }
    }

    private void runSteps(List<Step> steps) throws Exception {
        for (Step step : steps) {
            log.info("Running step {}", step.name);
            step.function.run();
        }
        log.info("Successfully ran steps\n{}", humanReadable(steps));
    }

    private static @NonNull String humanReadable(List<Step> steps) {
        return String.join("\n", steps.stream().map(x -> x.name).toList());
    }

}
