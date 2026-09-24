package health.emrys.afon.polaris;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.ConfigUtils.cloudPropertySet;
import static health.emrys.afon.config.ConfigUtils.cloudPropertyValue;

public class PolarisConstants {

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

    public static final String POLARIS_INTERNAL_SERVICE_NAME = "polaris-internal";

    /**
     * The credentials Polaris needs to vend access to storage in each cloud.
     * GCP has none here, as its credentials are provided via the
     * {@link AfonProperty#GOOGLE_APPLICATION_CREDENTIALS} file, which the caller must mount.
     */
    private static final Map<AfonCloud, Set<AfonProperty>> CLOUD_CREDENTIALS = Map.of(
        AfonCloud.AWS, Set.of(AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION),
        AfonCloud.AZURE, Set.of(AZURE_APP_SECRET, AZURE_APP_ID, AZURE_TENANT_ID),
        AfonCloud.GCP, Set.of());

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
     *
     * The clouds are those selected by {@link health.emrys.afon.config.ConfigUtils#cloudPropertyValue()},
     * or all clouds if none is selected.
     */
    public static Map<AfonProperty, String> getPolarisProperties(){
        List<AfonCloud> clouds = cloudPropertySet()
            ? Arrays.stream(AfonCloud.values())
                    .filter(cloud -> cloud.toString().equalsIgnoreCase(cloudPropertyValue()))
                    .toList()
            : List.of(AfonCloud.values());
        return getPolarisProperties(clouds);
    }

    /**
     * As {@link #getPolarisProperties()} but only for the given cloud.
     */
    public static Map<AfonProperty, String> getPolarisProperties(AfonCloud cloud){
        return getPolarisProperties(List.of(cloud));
    }

    /**
     * The credential properties Polaris needs to vend access to storage in the given cloud,
     * keyed to their Polaris env var names.
     */
    public static Map<AfonProperty, String> getPolarisCredentialProperties(AfonCloud cloud){
        return toEnvNames(CLOUD_CREDENTIALS.get(cloud));
    }

    private static Map<AfonProperty, String> getPolarisProperties(List<AfonCloud> clouds){
        Set<AfonProperty> set = new HashSet<>(Set.of(POLARIS_ID, POLARIS_SECRET));
        clouds.forEach(cloud -> set.addAll(CLOUD_CREDENTIALS.get(cloud)));
        return toEnvNames(set);
    }

    private static Map<AfonProperty, String> toEnvNames(Set<AfonProperty> properties){
        return properties.stream().collect(Collectors.toMap(property -> property, PolarisConstants::polarisEnvNameFor));
    }

    private static String polarisEnvNameFor(AfonProperty property) {
        return switch (property) {
            case AZURE_APP_ID -> "AZURE_CLIENT_ID";
            case AZURE_APP_SECRET -> "AZURE_CLIENT_SECRET";
            default -> property.getName();
        };
    }

}
