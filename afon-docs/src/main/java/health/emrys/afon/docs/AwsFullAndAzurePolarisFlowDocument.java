package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class AwsFullAndAzurePolarisFlowDocument extends AbstractFlowDocument {

    public AwsFullAndAzurePolarisFlowDocument(AfonPropertyProvider properties) {
        super(properties, "aws-full-and-azure-polaris");
    }

    @Override
    public String getFilename() {
        return "flow_aws_full_and_azure_polaris.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup the AWS full stack and the Azure Polaris stack, one after the other
                """;
    }

}
