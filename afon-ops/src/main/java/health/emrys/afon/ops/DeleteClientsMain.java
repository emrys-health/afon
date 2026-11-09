package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.ops.kube.tools.KeyCloakTunnel;
import health.emrys.afon.security.keycloak.KeyCloakProvider;

/**
 * Deletes a KeyCloak client, through a port-forward to the KeyCloak pod of the cluster of the current kubectl context, as the
 * ingress of KeyCloak may not admit this machine to its admin API, see {@link KeyCloakTunnel}.
 */
public class DeleteClientsMain {

    public static void main(String[] args) throws Exception {
        String               cloudName        = args[0];
        String               clientName       = args[1];
        AfonCloud            cloud            = AfonCloud.valueOf(cloudName.toUpperCase());
        AfonPropertyProvider propertyProvider = new FilePropertyProvider().forCloud(cloud);
        KeyCloakTunnel.run(cloud, propertyProvider,
                           endpoint -> new KeyCloakProvider(propertyProvider, endpoint).deleteClient(clientName));
    }

}
