package health.emrys.afon.spark;

import health.emrys.afon.config.AfonPropertyProvider;
import org.apache.spark.SparkConf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KeyCloakSparkWarehouseConfigForTesting extends KeyCloakSparkWarehouseConfig {

    private final CatalogWarehouse[] catalogWarehouses;

    public KeyCloakSparkWarehouseConfigForTesting(AfonPropertyProvider properties,
                                                  CatalogWarehouse[] catalogWarehouses) {
        super(properties);
        this.catalogWarehouses = catalogWarehouses;
    }

    protected String[] catalogNames() {
        List<String> list = new ArrayList<>(Arrays.asList(super.catalogNames()));
        Arrays.stream(catalogWarehouses).forEach(x -> list.add(x.catalog()));
        return list.toArray(new String[0]);
    }

    @Override
    protected void configure(SparkConf sparkConf, String[] catalogNames) {
        super.configure(sparkConf, catalogNames);
        for  (CatalogWarehouse catalogWarehouse : catalogWarehouses) {
            configureWith(sparkConfigs(catalogWarehouse.warehouse(), catalogWarehouse.catalog()), sparkConf);
        }
    }

}
