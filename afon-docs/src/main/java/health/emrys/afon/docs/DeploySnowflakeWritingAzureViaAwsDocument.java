package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeploySnowflakeWritingAzureViaAwsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

public class DeploySnowflakeWritingAzureViaAwsDocument extends AbstractDocument {

    public DeploySnowflakeWritingAzureViaAwsDocument(AfonPropertyProvider properties) { super(properties); }

    @Override
    public String getFilename() {
        return "snowflake_writes_to_azure.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return """
                ## What it does
                This e2e test has Snowflake writing to Azure blob storage, registering its
                metadata with Polaris then running Spark against Polaris to see the same data.
                
                """;
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeploySnowflakeWritingAzureViaAwsMain().stepsUsing(stepOperations);
    }

}
