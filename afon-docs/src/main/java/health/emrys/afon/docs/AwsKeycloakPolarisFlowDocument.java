package health.emrys.afon.docs;

import health.emrys.afon.config.AfonPropertyProvider;

public class AwsKeycloakPolarisFlowDocument extends AbstractFlowDocument {

    public AwsKeycloakPolarisFlowDocument(AfonPropertyProvider properties) {
        super(properties, "aws-keycloak-and-polaris");
    }

    @Override
    public String getFilename() {
        return "flow_aws_keycloak_and_polaris.md";
    }

    @Override
    protected String getSupplementaryIntro() {
        return """
                Setup the AWS Keycloak stack and the AWS Polaris stack, one after the other
                """;
    }

}
