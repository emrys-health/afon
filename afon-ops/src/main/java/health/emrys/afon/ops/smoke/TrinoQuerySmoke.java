package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.config.OpsDefaults;
import org.slf4j.Logger;

import java.sql.*;
import java.util.Properties;

/**
 * To run against Kubernetes, run this first:
 * <pre>kubectl port-forward svc/trino 8080:8080</pre>
 * Populate the cloud Polaris with something like:
 * <pre>
 ./afon.sh -a exec --exec-class ops.RegisterPolarisTableMain --exec-args "GCP acceptancetests smoketesttable260601231212 gs://emrys_afon_core/acceptancetests/smoketesttable260601231212/metadata/00000-0cf4c26f-668b-474c-9542-618d3fba2e19.metadata.json  emryspolaris.click"
 </pre>
 * or <pre>
 ./afon.sh -a exec --exec-class ops.RegisterPolarisTableMain --exec-args "AZURE acceptancetests smoketesttable260601231212 abfss://afonstorage@afonstorageaccount.dfs.core.windows.net/acceptancetests/smoketesttable260601231212/metadata/00001-ba11f3b6-6920-4007-9368-ab913506716f.metadata.json  emryspolaris.click"
 </pre>
 *
 */
public class TrinoQuerySmoke {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TrinoQuerySmoke.class);

    private final String url;

    public TrinoQuerySmoke(String url) {
        this.url = url;
    }

    public String query(String query) throws SQLException {
        // 2. Set up connection properties (User is required by Trino)
        Properties properties = new Properties();
        properties.setProperty("user", "arbitrary");
        // If your cluster requires SSL or Password authentication:
        // properties.setProperty("password", "your_password");
        // properties.setProperty("SSL", "true");

        logger.info("Connecting to Trino");

        // 4. Execute the query using try-with-resources
        logger.info("Executing {} against {}", query, url);
        try (Connection connection = DriverManager.getConnection(url, properties);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {

            // 5. Iterate through the results
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
        }
        return "empty, like my life";
    }

    /**
     * @param args For the table that is not the default one, pass an argument like:
     *             gcp.acceptancetests.smoketesttable260601231212
     *             or
     *             azure.acceptancetests.smoketesttable260601231212
     *             after running the mvn commands above
     */
    public static void main(String[] args) {
        String url = "jdbc:trino://localhost:8080/aws/admin";
        try {
            // for other clouds try:
            // gcp.acceptancetests.smoketesttable260601231212
            // or
            // azure.acceptancetests.smoketesttable260601231212
            String sql = "SELECT COUNT(*) FROM ";
            if (args.length > 0) {
                sql = sql + args[0];
            } else {
                sql = sql + OpsDefaults.POLARIS_NAMESPACE_EXTERNAL + "." + OpsDefaults.POLARIS_TABLE_NAME;
            }
            String result = new TrinoQuerySmoke(url).query(sql);
            System.out.println(result);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

}