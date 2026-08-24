package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.DeploySnowflakeReadingAzureViaAwsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;

import static health.emrys.afon.config.AfonCloud.AZURE;

public class DeploySnowflakeReadingAzureViaAwsDocument extends AbstractDocument {

    public DeploySnowflakeReadingAzureViaAwsDocument(AfonPropertyProvider properties) {super(properties);}

    @Override
    public String getFilename() {
        return "snowflake_reads_azure.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return SnowflakeDocumentUtils.getSupplementaryIntro(AZURE);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeploySnowflakeReadingAzureViaAwsMain().stepsUsing(stepOperations);
    }

}
