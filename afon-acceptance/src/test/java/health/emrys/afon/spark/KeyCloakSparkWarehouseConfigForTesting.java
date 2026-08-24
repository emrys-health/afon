package health.emrys.afon.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import org.bouncycastle.util.Arrays;

public class KeyCloakSparkWarehouseConfigForTesting extends KeyCloakSparkWarehouseConfig {

    private final String catalog;

    public KeyCloakSparkWarehouseConfigForTesting(AfonPropertyProvider properties, String catalog) {
        super(properties);
        this.catalog = catalog;
    }

    protected String[] cloudProviders() {
        return Arrays.append(
            super.cloudProviders(),
            catalog);
    }
}
