package health.emrys.afon.core.configuration;

import health.emrys.afon.config.AfonProperties;
import health.emrys.afon.spark.SparkWarehouseConfig;

import java.util.Map;

import static java.lang.String.format;

public class SparkContextConfigurationPrinter {
    public static final void main(String[] args) {
        SparkWarehouseConfig app = new SparkWarehouseConfig("{ACCESS_TOKEN}",
                                                            "{CLIENT_ID}",
                                                            "{CLIENT_SECRET}",
                                                            "{POLARIS_HOST}");
        System.out.println("""
from pyspark.sql import SparkSession

spark = (SparkSession.builder\s
    .appName("practice")\s
    .master("spark://emrys-master-svc:7077")\s
    .config("spark.driver.host", "$DRIVER_HOST")\s
    .config("spark.driver.port", "7078")
                                   """);

        for (AfonProperties.SupportedCloudsEnum cloudProvider : AfonProperties.SupportedCloudsEnum.values()) {
            Map<String, String> configs = app.sparkConfigs(cloudProvider);
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                System.out.println(format("    .config(\"%s\", f\"%s\")", entry.getKey(), entry.getValue()));
            }
            System.out.println();
        }
    }
}
