package health.emrys.afon.docs;

import health.emrys.afon.config.AfonProperties;

public class DocumentSnowflakeUtils {

    public static String getSupplementaryIntro(AfonProperties.SupportedCloudsEnum warehouse) {
        return String.format("""
               ### Summary of this deployment
               
               This deployment uses Spark running locally but talking to the AWS Spark Connector to create an
               Iceberg table in %s. This table is automatically registered in the AWS Polaris as part of 
               the creation process.
               
               We then point Snowflake at this Polaris running in our real estate and run a SF query against it.
               """, warehouse.name());
    }

}
