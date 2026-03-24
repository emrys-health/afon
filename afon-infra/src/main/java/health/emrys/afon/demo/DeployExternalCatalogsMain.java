package health.emrys.afon.demo;

import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.cdk.AwsConstruct.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.cdk.AzureKubernetesPolaris.AZURE_DOMAIN_NAME;
import static health.emrys.afon.demo.Steps.*;

public class DeployExternalCatalogsMain {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DeployExternalCatalogsMain.class);

    private static final List<Step> steps
        = List.of(
        CLEAN_AWS,
        AWS_POLARIS,
        waitFor(AWS_POLARIS_DOMAIN_NAME),
        CLEAN_AZ,
        AZ_POLARIS,
        waitFor(AZURE_DOMAIN_NAME),
        AZ_POLARIS_FOREIGN_CATALOG,
        AZ_POLARIS_EXTERNAL_AWS
                 );

    public static void main(String[] args) {
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
    }

}
