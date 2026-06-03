package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.polaris.core.admin.model.*;
import org.apache.polaris.core.entity.PolarisEntityConstants;

import java.util.HashMap;
import java.util.Map;

public abstract class CloudConfigForPolaris {

    public static final String METADATA_KEY = "EMRYS_METADATA";

    private final JsonMaker jsonMaker;

    protected CloudConfigForPolaris(JsonMaker jsonMaker) {
        this.jsonMaker = jsonMaker;
    }

    public String catalogAdminGrants() throws IncorrectConfigException {
        return toJson(
            new CatalogGrant(CatalogPrivilege.TABLE_WRITE_DATA, GrantResource.TypeEnum.CATALOG)
                     );
    }

    public String principalCreation() throws IncorrectConfigException {
        return toJson(
            Principal.builder().setClientId("client_id").setName("client_name").build()
                     );
    }

    public String principalRoleCreation() throws IncorrectConfigException {
        return toJson(
            PrincipalRole.builder().setName("principle_role").build()
                     );
    }

    public String catalogRoleCreation(String catalogRoleName) throws IncorrectConfigException {
        return toJson(
            CatalogRole.builder().setName(catalogRoleName).build()
                     );
    }

    public String catalogRoleGrant(CatalogPrivilege privelege) throws IncorrectConfigException {
        return toJson(
            AddGrantRequest.builder().setGrant(
                CatalogGrant.builder()
                            .setPrivilege(privelege)
                            .setType(GrantResource.TypeEnum.CATALOG)
                            .build()
                                              )
                .build()
              );
    }

    public String catalogRoles() throws IncorrectConfigException {
        return toJson(
            new CatalogRole(PolarisEntityConstants.getNameOfCatalogAdminRole())
                     );
    }

    public abstract String catalogConfig() throws IncorrectConfigException;

    public abstract String warehouseName();

    protected String toJson(Object object) throws IncorrectConfigException {
        try {
            return jsonMaker.toJson(object);
        } catch (JsonProcessingException e) {
            throw new IncorrectConfigException(this.toString(), e);
        }
    }

    protected Catalog createCatalog(String bucket,
                                    StorageConfigInfo storageConfigInfo) {
        CatalogProperties catalogProperties = new CatalogProperties(bucket);
        catalogProperties.putAll(metadata());
        return new Catalog(
            Catalog.TypeEnum.INTERNAL,
            warehouseName(),
            catalogProperties,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            null,
            storageConfigInfo
        );
    }
    public Catalog createCatalog() {
        return createCatalog(getBucketUri(), getStorageConfigInfo());
    }
    abstract protected StorageConfigInfo getStorageConfigInfo();
    abstract protected String getBucketUri();

    protected Map<String, String> metadata() {
        var properties = new HashMap<String, String>();
        properties.put(METADATA_KEY, "Emrys catalog for " + warehouseName());
        return properties;
    }

}
