package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.polaris.core.admin.model.CatalogGrant;
import org.apache.polaris.core.admin.model.CatalogPrivilege;
import org.apache.polaris.core.admin.model.CatalogRole;
import org.apache.polaris.core.admin.model.GrantResource;
import org.apache.polaris.core.entity.PolarisEntityConstants;

public abstract class CloudConfigForPolaris {

    private final JsonMaker jsonMaker;

    protected CloudConfigForPolaris(JsonMaker jsonMaker) {
        this.jsonMaker = jsonMaker;
    }

    public String catalogAdminGrants() throws IncorrectConfigException {
        return toJson(
            new CatalogGrant(CatalogPrivilege.TABLE_WRITE_DATA, GrantResource.TypeEnum.CATALOG)
        );
    }

    public String catalogRoles() throws IncorrectConfigException {
        return toJson(
            new CatalogRole(PolarisEntityConstants.getNameOfCatalogAdminRole())
        );
    }

    public abstract String catalogConfig() throws IncorrectConfigException;

    public abstract  String warehouseName();

    protected String toJson(Object object) throws IncorrectConfigException {
        try {
            return jsonMaker.toJson(object);
        } catch (JsonProcessingException e) {
            throw new IncorrectConfigException(this.toString(), e);
        }
    }

}
