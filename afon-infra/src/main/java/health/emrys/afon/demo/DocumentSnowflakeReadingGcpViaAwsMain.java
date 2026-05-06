package health.emrys.afon.demo;

import java.io.IOException;
import java.util.List;

public class DocumentSnowflakeReadingGcpViaAwsMain extends AbstractDocumentMetadata {

    public static final String SNOWFLAKE_TABLE_NAME = "my_gcp_table_id";

    @Override
    public String getSupplementaryIntro() {
        return """
               ### Summary of this deployment
               
               This deployment uses Spark running locally but talking to the AWS Spark Connector to create an
               Iceberg table in GCP. This table is automatically registered in the AWS Polaris as part of 
               the creation process.
               
               We then point Snowflake at this Polaris running in our real estate and run a SF query against it.
               """;
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
