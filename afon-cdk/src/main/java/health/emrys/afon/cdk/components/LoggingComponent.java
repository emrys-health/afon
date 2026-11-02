package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.aws.AwsCloudProvider;
import health.emrys.afon.cdk.aws.AwsPolarisLogShipper;
import health.emrys.afon.cdk.azure.AzureCloudProvider;
import health.emrys.afon.cdk.azure.AzurePolarisLogShipper;
import health.emrys.afon.cdk.gcp.GcpCloudProvider;
import health.emrys.afon.cdk.gcp.GcpPolarisLogShipper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.AWS_REGION;
import static health.emrys.afon.config.AfonProperty.AZURE_CONNECTION_STRING;
import static health.emrys.afon.config.AfonProperty.AZURE_STORAGE_ACCOUNT;
import static health.emrys.afon.config.AfonProperty.GCP_HMAC_ACCESS_ID;
import static health.emrys.afon.config.AfonProperty.GCP_HMAC_SECRET;
import static health.emrys.afon.config.AfonProperty.GCP_REGION;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Ships the logs of Polaris to the bucket (AWS, GCP) or blob container (Azure) of {@code <CLOUD>_POLARIS_LOG_BUCKET}, with a log
 * shipper of the cloud the stack is deployed to.  Ships nothing if the property is not set.
 * <p>
 * Polaris depends on the log shipper, so that on destroy it is gone before the shipper is.  On GCP the logs are written with the HMAC key
 * of {@code GCP_HMAC_ACCESS_ID} and {@code GCP_HMAC_SECRET}, which must then be set.
 */
public class LoggingComponent implements StackComponent {

    private static final Logger logger = getLogger(LoggingComponent.class);

    @Override
    public void deploy(StackContext context) {
        AfonCloud            cloud      = context.stackId().cloud();
        AfonPropertyProvider properties = context.properties();
        String logBucket = properties.getCloudValue(cloud, AfonCloudProperty.POLARIS_LOG_BUCKET, "").trim();
        if (logBucket.isEmpty()) {
            logger.info("Stack {} ships no Polaris logs, as {}_POLARIS_LOG_BUCKET is not set", context.stackId(),
                        cloud.toString().toUpperCase());
            return;
        }
        switch (cloud) {
            case AWS -> new AwsPolarisLogShipper(
                (AwsCloudProvider) context.platform(),
                logBucket,
                properties.getValue(AWS_REGION))
                .deploy(context.stack(), context.platform().getDependables());
            case AZURE -> new AzurePolarisLogShipper(
                (AzureCloudProvider) context.platform(),
                properties.getValue(AZURE_STORAGE_ACCOUNT),
                logBucket,
                properties.getValue(AZURE_CONNECTION_STRING))
                .deploy(context.stack(), context.platform().getDependables());
            case GCP -> {
                // which Fluent Bit needs to write the logs, else it would only fail once the stack is deployed
                String accessId = properties.getValue(GCP_HMAC_ACCESS_ID, "").trim();
                String secret   = properties.getValue(GCP_HMAC_SECRET, "").trim();
                if (accessId.isEmpty() || secret.isEmpty()) {
                    throw new IllegalArgumentException("GCP_HMAC_ACCESS_ID and GCP_HMAC_SECRET must be set, which shipping Polaris' logs "
                                                       + "to GCS needs: set them in secret-gcp.properties");
                }
                new GcpPolarisLogShipper(
                    (GcpCloudProvider) context.platform(),
                    logBucket,
                    properties.getValue(GCP_REGION),
                    accessId,
                    secret)
                    .deploy(context.stack(), context.platform().getDependables());
            }
            default -> throw new IllegalStateException("Unreachable: " + cloud);
        }
    }

    @Override
    public List<Class<? extends StackComponent>> requires() {
        return List.of(PolarisComponent.class);
    }
}
