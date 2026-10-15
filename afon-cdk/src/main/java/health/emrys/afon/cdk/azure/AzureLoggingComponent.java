package health.emrys.afon.cdk.azure;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.aws.AwsCloudProvider;
import health.emrys.afon.cdk.aws.AwsPolarisLogShipper;
import health.emrys.afon.cdk.components.PolarisComponent;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static org.slf4j.LoggerFactory.getLogger;

public class AzureLoggingComponent implements StackComponent {

    private static final Logger logger = getLogger(AzureLoggingComponent.class);

    @Override
    public void deploy(StackContext context) {
        AfonPropertyProvider properties = context.properties();
        String               logBucket  = properties.getValue(AZURE_POLARIS_LOG_BUCKET, "").trim();
        if (logBucket.isEmpty()) {
            logger.info("Stack {} ships no Polaris logs to S3, as {} is not set",
                        context.stackId(),
                        AWS_POLARIS_LOG_BUCKET);
        } else {
            // Polaris depends on the log shipper so that, on destroy, it is gone before the shipper is.
            new AzurePolarisLogShipper(
                (AzureCloudProvider)context.platform(),
                properties.getValue(AZURE_STORAGE_ACCOUNT),
                logBucket,
                properties.getValue(AfonProperty.AZURE_CONNECTION_STRING)
            ).deploy(context.stack(), context.platform().getDependables());
        }
    }

    @Override
    public List<Class<? extends StackComponent>> requires() {
        return List.of(PolarisComponent.class);
    }

}
