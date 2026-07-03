package health.emrys.afon.spark;

import health.emrys.afon.config.AfonProperties;

import java.util.Properties;

public class SparkWarehouseConfigFactory {

    public static SparkWarehouseConfigFactory getFactory() {
        return new SparkWarehouseConfigFactory();
    }

    public SparkWarehouseConfig getConfig(
        String clientId,
        String clientSecret,
        String host,
        String polarisProtocol) {
        return new SparkWarehouseConfig(clientId, clientSecret, host, polarisProtocol);
    }

    public SparkWarehouseConfig getConfig(
        String clientId,
        String clientSecret,
        String host) {
        return new SparkWarehouseConfig(clientId, clientSecret, host);
    }

    public SparkWarehouseConfig getConfig(Properties properties) {
        return new SparkWarehouseConfig(properties);
    }

}
