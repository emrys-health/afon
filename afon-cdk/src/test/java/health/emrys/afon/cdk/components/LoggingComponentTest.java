package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonCloud.GCP;
import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_LOG_BUCKET;
import static health.emrys.afon.config.AfonProperty.AZURE_POLARIS_LOG_BUCKET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingComponentTest {

    private final AfonPropertyProvider properties = new FilePropertyProvider("../templates/config");

    private StackContext contextOf(StackId stackId) {
        // no platform: the component only needs it to ship logs
        return new StackContext(null, stackId, null, properties);
    }

    @Test
    void shipsNothingOnAwsAndAzureWhenTheLogBucketOfTheCloudIsNotSet() {
        properties.setValue(AWS_POLARIS_LOG_BUCKET, "");
        properties.setValue(AZURE_POLARIS_LOG_BUCKET, "");
        assertThatCode(() -> new LoggingComponent().deploy(contextOf(new StackId(AWS, "x")))).doesNotThrowAnyException();
        assertThatCode(() -> new LoggingComponent().deploy(contextOf(new StackId(AZURE, "x")))).doesNotThrowAnyException();
    }

    @Test
    void usesTheLogBucketOfTheCloudOfTheStack() {
        // only the Azure bucket is set, so AWS still ships nothing, and Azure goes on to need its platform
        properties.setValue(AWS_POLARIS_LOG_BUCKET, "");
        properties.setValue(AZURE_POLARIS_LOG_BUCKET, "container");
        assertThatCode(() -> new LoggingComponent().deploy(contextOf(new StackId(AWS, "x")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new LoggingComponent().deploy(contextOf(new StackId(AZURE, "x"))))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void cannotBeDeployedToACloudWithoutALogShipper() {
        assertThatThrownBy(() -> new LoggingComponent().deploy(contextOf(new StackId(GCP, "x"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Stack gcp-x cannot ship Polaris logs, not supported for cloud gcp");
    }

    @Test
    void needsPolarisBeforeIt() {
        assertThat(new LoggingComponent().requires()).containsExactly(PolarisComponent.class);
        assertThat(new LoggingComponent()).isInstanceOf(StackComponent.class);
    }
}
