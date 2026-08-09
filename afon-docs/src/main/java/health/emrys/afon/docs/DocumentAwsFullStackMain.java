package health.emrys.afon.docs;

import health.emrys.afon.cdk.SparkWorkloads;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.DeployAwsFullStackMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DocumentAwsFullStackMain extends AbstractDocumentMetadata {

    @Override
    public String getFilename() {
        return "aws_full_stack.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return "This brings the full stack (Jupyter, Polaris, Spark etc) up on AWS.";
    }

    public String getFurtherNotes() {
        String polarisDomainName =
            AfonProperties.AWS_POLARIS_DOMAIN_NAME.getValueFrom(new FilePropertyProvider().getProperties());
        return String.format(
            """
            # Problems starting Spark Connect
            
            There appears to be a race condition so that Spark Connect is killed as soon as it starts
            (see issue [#65](https://github.com/emrys-health/afon-core/issues/65)). 
            If this happens, run:
            
            ```
            kubectl delete sparkapp spark-connector
            %s
            ```
            
            # Problems destroying the stack?
            
           Generate and run cleanup script (more than once if required):
            ```shell
           ./afon.sh -a exec --exec-class cdk.aws.AwsCleanupHelper --exec-args aws-cleanup.sh
            ```
            """,
            SparkWorkloads.startConnectCommand(polarisDomainName));
    }


    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeployAwsFullStackMain().stepsUsing(stepOperations);
    }

    public static void main(String[] args) {
        new Documenter(new DocumentAwsFullStackMain()).writeDocumentation();
    }

}
