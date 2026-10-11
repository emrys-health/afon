package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class CrossCloudAzureViaAwsFlowDocument extends AbstractFlowDocument {

    public CrossCloudAzureViaAwsFlowDocument(AfonPropertyProvider properties) {
        super(properties, "cross-cloud-azure");
    }

    @Override
    public String getFilename() {
        return "flow_cross_cloud_azure.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup the AWS full stack and the Azure Polaris stack, then run Spark on AWS writing through the AWS Polaris to the external catalog hosted by the Azure Polaris
                """;
    }

}
