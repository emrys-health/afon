package health.emrys.afon.ops;

import health.emrys.afon.cluster.EndpointPolarisConfiguration;

public class ConfigureEndpointPolarisMain {

    public static void main(String[] args) {
        try {
            try (EndpointPolarisConfiguration app = new EndpointPolarisConfiguration()) {
                app.configure(args);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}