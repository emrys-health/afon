package health.emrys.afon.docs;

import health.emrys.afon.cdk.SparkWorkloads;
import health.emrys.afon.cdk.aws.AwsNuke;
import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;

public class DocumentFullStackAwsMain extends AbstractDocumentMetadata {

    private static final Logger log = getLogger(DocumentFullStackAwsMain.class);

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentFullStackAwsMain()).writeDocumentation();
    }

    @Override
    public String getSupplementaryIntro() {
        return "This brings the full stack (Jupyter, Polaris, Spark etc) up on AWS.";
    }

    @Override
    public String getFilename() {
        return "full_stack_aws.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return List.of(
            stepOperations.cleanLocalAws(),
            stepOperations.deployFullAwsCluster(),
            stepOperations.waitForAwsPolaris(),
            stepOperations.initializeAllCatalogsInAws(),
            stepOperations.checkAwsSparkConnectorTalkingToAws()
                      );
    }

    public String getFurtherNotes() {
        var    nuke              = new AwsNuke();
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
            nuke.cleanUpHint());
    }
}
