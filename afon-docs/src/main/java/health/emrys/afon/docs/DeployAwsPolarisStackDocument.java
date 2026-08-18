package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeployAwsPolarisStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeployAwsPolarisStackDocument extends AbstractDocument {

    public DeployAwsPolarisStackDocument(AfonPropertyProvider properties) {
        super(properties);
    }

    @Override
    public String getFilename() {
        return "aws_polaris_stack.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Creates an AWS environment with a Polaris instance populated with the test catalogs
                """;
    }

    @Override
    public String getFurtherNotes() {
        return String.format("""
            # Troubleshooting

            ### Problems destroying the stack?
            
            Generate and run cleanup script:
            ```shell
            ./afon.sh -a exec --exec-class cdk.aws.AwsCleanupHelper --exec-args aws-cleanup.sh
            ```
            """);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeployAwsPolarisStackMain().stepsUsing(stepOperations);
    }

}
