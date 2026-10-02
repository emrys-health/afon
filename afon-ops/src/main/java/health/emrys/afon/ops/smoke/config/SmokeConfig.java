package health.emrys.afon.ops.smoke.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.AWS_EGRESS_IP;
import static health.emrys.afon.config.AfonProperty.TRE_CLOUD;

public class SmokeConfig {

    public static final String KUBERNETES_NAMESPACE = "default";
    public static final String SPARK_CONNECTOR_POD = "spark-connector-0-driver";
    public static final long PROGRESS_INTERVAL_SECONDS = 10;
    public static final long CALL_TIMEOUT_SECONDS = 30;
    public static final AfonCloud DEFAULT_TRE_CLOUD = AfonCloud.AWS;

    private final AfonPropertyProvider properties;

    public SmokeConfig(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    /**
     * @return the cloud of the stack hosting the TRE, from {@code TRE_CLOUD}, or {@link #DEFAULT_TRE_CLOUD} if it is
     * not set or empty
     * @throws IllegalArgumentException if {@code TRE_CLOUD} is not one of the clouds
     */
    public AfonCloud cloud() {
        String cloud = properties.getValue(TRE_CLOUD, "").trim();
        if (cloud.isEmpty()) {
            return DEFAULT_TRE_CLOUD;
        }
        try {
            return AfonCloud.valueOf(cloud.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid TRE_CLOUD '" + cloud + "', which should be one of aws, azure or gcp", e);
        }
    }

    /**
     * @return The IP address of the cluster if it's been defined in config else "undefined"
     */
    public String egressIP() {
        AfonCloud cloud = cloud();
        switch (cloud){
            case AWS:
                return properties.getValue(AWS_EGRESS_IP, "undefined");
            default:
                throw new IllegalStateException("EGRESS_IP not defined for cloud: " + cloud);
        }
    }

    /**
     * @return the interval, in seconds, at which to log that a call is still waiting for a response
     */
    public long progressIntervalSeconds() {
        return PROGRESS_INTERVAL_SECONDS;
    }

    /**
     * @return the time, in seconds, after which to give up waiting for the response of a call
     */
    public long callTimeoutSeconds() {
        return CALL_TIMEOUT_SECONDS;
    }

}
