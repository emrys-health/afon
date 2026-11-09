package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

import java.io.IOException;

public class DeleteClientsMain {

    public static void main(String[] args) throws IOException {
        String               cloudName        = args[0];
        String               clientName       = args[1];
        AfonCloud            cloud            = AfonCloud.valueOf(cloudName.toUpperCase());
        AfonPropertyProvider propertyProvider = new FilePropertyProvider().forCloud(cloud);
        new KeyCloakProvider(propertyProvider).deleteClient(clientName);
    }

}
