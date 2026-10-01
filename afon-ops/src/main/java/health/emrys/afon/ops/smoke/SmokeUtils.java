package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;

import static health.emrys.afon.config.AfonProperty.AWS_EGRESS_IP;

public class SmokeUtils {

    public static final String KUBERNETES_NAMESPACE = "default";

    public static final String SPARK_CONNECTOR_POD = "spark-connector-0-driver";

    private final AfonPropertyProvider properties;

    SmokeUtils(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    AfonCloud cloudToTest() {
        return AfonCloud.AWS; // Total cheat. We only have an AWS full stack at the moment. TODO make configurable
    }

    /**
     * @return The IP address of the cluster if it's been defined in config else "undefined"
     */
    String egressIP() {
        return properties.getValue(AWS_EGRESS_IP, "undefined"); // TODO assumes this is AWS. Make configurable
    }

}
