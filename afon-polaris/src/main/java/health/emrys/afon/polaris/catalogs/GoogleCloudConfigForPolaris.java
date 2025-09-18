package health.emrys.afon.polaris.catalogs;

public class GoogleCloudConfigForPolaris implements CloudConfigForPolaris {

    private final String warehouseName;

    public GoogleCloudConfigForPolaris(String warehouseName) {
        this.warehouseName = warehouseName;
    }

    @Override
    public String catalogConfig() {
        return "";
    }

    @Override
    public String catalogAdminGrants() {
        return "";
    }

    @Override
    public String catalogRoles() {
        return "";
    }

    @Override
    public String warehouseName() {
        return warehouseName;
    }
}
