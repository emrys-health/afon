package health.emrys.afon.polaris.catalogs;

public class GoogleCloudConfigForPolaris extends CloudConfigForPolaris {

    private final String warehouseName;

    public GoogleCloudConfigForPolaris(String warehouseName) {
        super(new JsonMaker());
        this.warehouseName = warehouseName;
    }

    @Override
    public String catalogConfig() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public String warehouseName() {
        return warehouseName;
    }
}
