package health.emrys.afon.docs;

import health.emrys.afon.ops.DeploySnowflakeReadingAwsViaAwsMain;
import health.emrys.afon.ops.steps.Step;
import health.emrys.afon.ops.steps.StepOperations;

import java.util.List;
import java.util.Properties;

import static health.emrys.afon.config.AfonCloud.AWS;

public class DeploySnowflakeReadingAwsViaAwsDocument extends AbstractDocument {

    public DeploySnowflakeReadingAwsViaAwsDocument(Properties properties) {super(properties);}

    @Override
    public String getFilename() {
        return "snowflake_reads_aws.md";
    }

    @Override
    public String getSupplementaryIntro() {
        return SnowflakeDocumentUtils.getSupplementaryIntro(AWS);
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return new DeploySnowflakeReadingAwsViaAwsMain().stepsUsing(stepOperations);
    }

}
