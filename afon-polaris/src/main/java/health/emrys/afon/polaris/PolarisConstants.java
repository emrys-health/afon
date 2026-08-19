package health.emrys.afon.polaris;

import health.emrys.afon.config.AfonProperty;

import java.util.HashSet;
import java.util.Set;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.ConfigConstants.cloudPropertySet;
import static health.emrys.afon.config.ConfigConstants.cloudPropertyValue;

public class PolarisConstants {
    /**
     * 'Magic' namespace that is used for acceptance tests and infra
     */
    public static final String ACCEPTANCE_TEST_NAMESPACE = "acceptancetests";

    public static final int DEBUG_PORT = 5006;

    public static final String POLARIS_CMD
        = String.format("""
                            cd /deployments && java 
                            -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%d
                            -D"polaris.features.\\"SUPPORTED_CATALOG_CONNECTION_TYPES\\"=[\\"ICEBERG_REST\\", \\"HIVE\\"]" 
                            -D"polaris.features.\\"ENABLE_CATALOG_FEDERATION\\"=true" 
                            -XX:MaxRAMPercentage=80.0 
                            -XX:+UseParallelGC 
                            -XX:MinHeapFreeRatio=10 
                            -XX:MaxHeapFreeRatio=20 
                            -XX:GCTimeRatio=4 
                            -XX:AdaptiveSizePolicyWeight=90 
                            -XX:+ExitOnOutOfMemoryError 
                            -cp . 
                            -jar /deployments/quarkus-run.jar""", DEBUG_PORT).replaceAll("\\s+", " ").trim();

    public static final String POLARIS_IMAGE_NAME = "apache/polaris:1.5.0";

    public static final Set<AfonProperty> getPolarisProperties(){
        Set<AfonProperty> set = new HashSet<>();
        set.addAll(Set.of(POLARIS_ID, POLARIS_SECRET));
        if ( cloudPropertySet() ){
            switch ( cloudPropertyValue() ){
                case "aws":
                    set.addAll(Set.of(AWS_ACCESS_KEY_ID,
                            AWS_SECRET_ACCESS_KEY,
                            AWS_REGION));
                    break;
                case "azure":
                    set.addAll(Set.of(AZURE_CLIENT_SECRET,
                            AZURE_CLIENT_ID,
                            AZURE_TENANT_ID));
                    break;
                default:
            }
        } else {
            // add all cloud providers
            set.addAll(Set.of(AWS_ACCESS_KEY_ID,
                    AWS_SECRET_ACCESS_KEY,
                    AWS_REGION, AZURE_CLIENT_SECRET,
                    AZURE_CLIENT_ID,
                    AZURE_TENANT_ID));

        }
        return set;
    }

}
