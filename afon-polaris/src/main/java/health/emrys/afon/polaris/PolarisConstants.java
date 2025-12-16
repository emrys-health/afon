package health.emrys.afon.polaris;

public class PolarisConstants {

    public static final String POLARIS_CMD = """
            cd /deployments && java 
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
            -jar /deployments/quarkus-run.jar""".replaceAll("\\s+", " ").trim();

    public static final String POLARIS_IMAGE_NAME = "apache/polaris:1.2.0-incubating";

}
