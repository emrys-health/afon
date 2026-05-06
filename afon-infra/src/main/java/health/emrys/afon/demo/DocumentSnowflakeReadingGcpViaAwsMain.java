package health.emrys.afon.demo;

import java.io.IOException;
import java.util.List;

public class DocumentSnowflakeReadingGcpViaAwsMain extends AbstractDocumentMetadata {

    public static final String SNOWFLAKE_TABLE_NAME = "my_gcp_table_id";

    @Override
    public String getSupplementaryIntro() {
        return "";
    }

    @Override
    public String getFilename() {
        return "snowflake.md";
    }

    @Override
    public List<Step> steps(StepOperations stepOperations) {
        return DeploySnowflakeReadingGcpViaAwsMain.steps(stepOperations);
    }

    public static void main(String[] args) throws IOException {
        new Documenter(new DocumentSnowflakeReadingGcpViaAwsMain()).writeDocumentation();
    }
}
