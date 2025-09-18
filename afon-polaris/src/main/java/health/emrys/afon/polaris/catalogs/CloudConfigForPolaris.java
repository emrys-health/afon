package health.emrys.afon.polaris.catalogs;

public interface CloudConfigForPolaris {

    String catalogConfig() throws IncorrectConfigException;

    String catalogAdminGrants() throws IncorrectConfigException;

    String catalogRoles() throws IncorrectConfigException;

    String warehouseName();

}
