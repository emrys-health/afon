package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.cdk.aws.AwsCloudProvider;
import health.emrys.afon.cdk.aws.AwsPolarisLogShipper;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.slf4j.Logger;

import java.util.List;

import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_LOG_BUCKET;
import static org.slf4j.LoggerFactory.getLogger;

public class LoggingComponent implements StackComponent {

    private static final Logger logger = getLogger(LoggingComponent.class);

    @Override
    public void deploy(StackContext context) {
        if (context.platform().cloud() == AfonCloud.AWS) {
            AfonPropertyProvider properties = context.properties();
            String               logBucket  = properties.getValue(AWS_POLARIS_LOG_BUCKET, "").trim();
            if (logBucket.isEmpty()) {
                logger.info("Stack {} ships no Polaris logs to S3, as {} is not set",
                            context.stackId(),
                            AWS_POLARIS_LOG_BUCKET);
            } else {
                // Polaris depends on the log shipper so that, on destroy, it is gone before the shipper is.
                AwsPolarisLogShipper.deploy(
                    context.stack(),
                    (AwsCloudProvider) context.platform(),
                    context.platform().getDependables(),
                    logBucket,
                    properties.getValue(AfonProperty.AWS_REGION));
            }
        }
    }

    @Override
    public List<Class<? extends StackComponent>> requires() {
        return List.of(PolarisComponent.class);
    }

}
