package health.emrys.afon.trino;

import java.sql.*;
import java.util.Properties;

/**
 * To run against Kubernetes, run this first:
 * <pre>kubectl port-forward svc/trino 8080:8080</pre>
 * Populate the cloud Polaris with something like:
 * <pre>
 ./afon.sh -a exec --exec-class ops.RegisterLocalPolarisMain --exec-args "GCP acceptancetests smoketesttable260601231212 gs://emrys_afon_core/acceptancetests/smoketesttable260601231212/metadata/00000-0cf4c26f-668b-474c-9542-618d3fba2e19.metadata.json  emryspolaris.click"
</pre>
 * or <pre>
 ./afon.sh -a exec --exec-class ops.RegisterLocalPolarisMain --exec-args "AZURE acceptancetests smoketesttable260601231212 abfss://afonstorage@afonstorageaccount.dfs.core.windows.net/acceptancetests/smoketesttable260601231212/metadata/00001-ba11f3b6-6920-4007-9368-ab913506716f.metadata.json  emryspolaris.click"
</pre>
 *
 */
public class SmokeTest {

    /**
     * @param args
     * For the table that is not the default one, pass an argument like:
     * gcp.acceptancetests.smoketesttable260601231212
     * or
     * azure.acceptancetests.smoketesttable260601231212
     * after running the mvn commands above
     */
    public static void main(String[] args) {
        String url = "jdbc:trino://localhost:8080/";
        try {
            // for other clouds try:
            // gcp.acceptancetests.smoketesttable260601231212
            // or
            // azure.acceptancetests.smoketesttable260601231212
            String sql = "SELECT COUNT(*) FROM ";
            if (args.length > 0) {
                sql = sql + args[0];
            } else {
                sql = sql + "aws.namespaceph.test_table";
            }
            String result = new SmokeTest(url).readTable(sql);
            System.out.println(result);
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    private final String url;

    public SmokeTest(String url) {
        this.url = url;
    }

    public String readTable(String sqlQuery) throws SQLException {
        // 2. Set up connection properties (User is required by Trino)
        Properties properties = new Properties();
        properties.setProperty("user", "arbitrary");
        // If your cluster requires SSL or Password authentication:
        // properties.setProperty("password", "your_password");
        // properties.setProperty("SSL", "true");

        System.out.println("Connecting to Trino...");

        // 4. Execute the query using try-with-resources
        try (Connection connection = DriverManager.getConnection(url, properties);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sqlQuery)) {

            // 5. Iterate through the results
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
        }
        return "empty, like my life";
    }
}