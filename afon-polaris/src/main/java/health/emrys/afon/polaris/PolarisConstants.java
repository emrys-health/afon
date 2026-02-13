package health.emrys.afon.polaris;

public class PolarisConstants {

    public static final int DEBUG_PORT = 5006;

    public static final String POLARIS_CMD = String.format("""
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

    public static final String POLARIS_IMAGE_NAME = "apache/polaris:1.3.0-incubating";

}
