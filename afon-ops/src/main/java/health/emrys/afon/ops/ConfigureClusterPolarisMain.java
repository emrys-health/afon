package health.emrys.afon.ops;

import health.emrys.afon.infra.ClusterPolarisConfiguration;

public class ConfigureClusterPolarisMain {

    public static void main(String[] args) {
        try {
            try (ClusterPolarisConfiguration app = new ClusterPolarisConfiguration()) {
                app.configure(args);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}