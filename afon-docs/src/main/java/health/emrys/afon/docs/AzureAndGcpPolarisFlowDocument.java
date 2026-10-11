package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class AzureAndGcpPolarisFlowDocument extends AbstractFlowDocument {

    public AzureAndGcpPolarisFlowDocument(AfonPropertyProvider properties) {
        super(properties, "azure-and-gcp-polaris");
    }

    @Override
    public String getFilename() {
        return "flow_azure_and_gcp_polaris.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup the Azure Polaris stack and the GCP Polaris stack, one after the other
                """;
    }

}
