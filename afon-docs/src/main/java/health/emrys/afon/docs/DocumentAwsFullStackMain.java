package health.emrys.afon.docs;

import health.emrys.afon.cdk.SparkWorkloads;
import health.emrys.afon.cdk.aws.AwsCleanupHelper;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static health.emrys.afon.config.AfonProperties.AfonCloud.AWS;
import static org.slf4j.LoggerFactory.getLogger;

public class DocumentAwsFullStackMain extends AbstractDocumentMetadata {

    private static final Logger log = getLogger(DocumentAwsFullStackMain.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentAwsFullStackMain()).writeDocumentation();
    }

    @Override
    public String getSupplementaryIntro() {
        return "This brings the full stack (Jupyter, Polaris, Spark etc) up on AWS.";
    }

    @Override
    public String getFilename() {
        return "aws_full_stack.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return List.of(
            stepOperations.deleteStack(AWS),
                stepOperations.deployFullStackIn(AfonProperties.AfonCloud.AWS),
            stepOperations.waitForPolarisIn(AWS),
            stepOperations.initializeAllCatalogsIn(AWS),
            stepOperations.checkAwsSparkWritingTo(AWS)
                      );
    }

    public String getFurtherNotes() {
        String polarisDomainName =
            AfonProperties.AWS_POLARIS_DOMAIN_NAME.getValueFrom(new AfonPropertiesLoader().getProperties());
        return String.format(
            """
            # Problems starting Spark Connect
            
            There appears to be a race condition so that Spark Connect is killed as soon as it starts
            (see [this ticket](https://github.com/emrys-health/afon-core/issues/65). 
            If this happens, run:
            
            ```
            kubectl delete sparkapp spark-connector
            %s
            ```
            
            # Problems destroying the stack?
            
            Run this *twice*:
            ```shell
            %s
            ```
            """,
            SparkWorkloads.startConnectCommand(polarisDomainName),
            new AwsCleanupHelper().cleanupCommands());
    }
}
