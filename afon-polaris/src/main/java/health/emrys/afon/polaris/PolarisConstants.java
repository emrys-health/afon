package health.emrys.afon.polaris;

import health.emrys.afon.config.AfonProperty;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.ConfigUtils.cloudPropertySet;
import static health.emrys.afon.config.ConfigUtils.cloudPropertyValue;

public class PolarisConstants {
    /**
     * 'Magic' namespace that is used for acceptance tests and ops
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

    // NOTE: polaris and polaris-admin-tool versions must be kept in sync
    public static final String POLARIS_IMAGE_NAME = "apache/polaris:1.5.0";
    public static final String POLARIS_ADMIN_TOOL_IMAGE_NAME = "apache/polaris-admin-tool:1.5.0";
    public static final String POLARIS_INTERNAL_SERVICE_NAME = "polaris-internal";

    /**
     * The Afon properties Polaris needs, keyed to the literal env var name Polaris's own process
     * must see them under. This is usually just {@link AfonProperty#getName()}, but Polaris vends
     * Azure ADLS credentials via the Azure SDK's {@code DefaultAzureCredential}, whose {@code
     * EnvironmentCredential} step requires the exact names {@code AZURE_CLIENT_ID} and {@code
     * AZURE_CLIENT_SECRET} — a contract of the Azure SDK.
     * Afon's properties {@link AfonProperty#AZURE_APP_ID}/{@link AfonProperty#AZURE_APP_SECRET}
     * match the Azure CLI's terminology instead.
     * The mapping is resolved here, in the one place that enumerates what Polaris needs,
     * so every caller gets the correct env var name.
     */
    public static Map<AfonProperty, String> getPolarisProperties(){
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
                    set.addAll(Set.of(AZURE_APP_SECRET,
                            AZURE_APP_ID,
                            AZURE_TENANT_ID));
                    break;
                default:
            }
        } else {
            // add all cloud providers
            set.addAll(Set.of(AWS_ACCESS_KEY_ID,
                    AWS_SECRET_ACCESS_KEY,
                    AWS_REGION, AZURE_APP_SECRET,
                    AZURE_APP_ID,
                    AZURE_TENANT_ID));

        }
        return set.stream().collect(Collectors.toMap(property -> property, PolarisConstants::polarisEnvNameFor));
    }

    private static String polarisEnvNameFor(AfonProperty property) {
        return switch (property) {
            case AZURE_APP_ID -> "AZURE_CLIENT_ID";
            case AZURE_APP_SECRET -> "AZURE_CLIENT_SECRET";
            default -> property.getName();
        };
    }

}
