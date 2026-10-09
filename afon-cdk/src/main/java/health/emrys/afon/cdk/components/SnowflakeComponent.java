package health.emrys.afon.cdk.components;

import health.emrys.afon.cdk.StackComponent;
import health.emrys.afon.cdk.StackContext;

import java.util.List;

/**
 * Snowflake, a query engine like Spark or Trino, reading the cloud's catalogs from Polaris at the cloud's
 * {@code POLARIS_DOMAIN_NAME}, and writing to the cloud's storage.
 * <p>
 * Unlike them it runs in Snowflake's cloud, not in the stack's cluster, so it has no workloads to deploy: it is reached
 * from the ops commands through a {@link health.emrys.afon.snowflake.SnowflakeConnectionFactory}, configured by
 * {@code SNOWFLAKE_JDBC_URL} and {@code SNOWFLAKE_PRIVATE_KEY}.  Having it in a stack states that the stack is queried by
 * Snowflake, so that what is run on Snowflake, see {@code SnowflakePlan}, is derived from the stack like for the other
 * engines.  So far, a connection factory is all of the support.
 */
public class SnowflakeComponent implements StackComponent {

    @Override
    public void deploy(StackContext context) {
        // nothing to deploy: Snowflake is a service outside of the stack, reached through a connection
    }

    @Override
    public List<Class<? extends StackComponent>> requires() {
        return List.of(PolarisComponent.class);
    }
}
