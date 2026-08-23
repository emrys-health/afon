package health.emrys.afon.ops.smoke;

import health.emrys.afon.cluster.EksClusterClient;
import health.emrys.afon.config.FilePropertyProvider;

import static java.lang.String.format;

public class EksClusterClientSmoke {

    private final EksClusterClient eksClient;

    public EksClusterClientSmoke(EksClusterClient eksClient) {
        this.eksClient = eksClient;
    }

    private void findService(String serviceName) {
        try {
            String serviceAddress = eksClient.getUniqueService(serviceName);
            System.out.println(format("Service is at ", serviceAddress));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static final void main(String[] args) {
        var properties = new FilePropertyProvider();
        var eksClient = new EksClusterClient(properties);
        EksClusterClientSmoke app = new EksClusterClientSmoke(eksClient);
        if (args.length == 1) {
            String serviceName = args[0];
            app.findService(serviceName);
        }
    }

}
