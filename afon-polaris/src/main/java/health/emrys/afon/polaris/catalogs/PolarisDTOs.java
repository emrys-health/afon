package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.polaris.core.admin.model.*;
import org.apache.polaris.core.entity.PolarisEntityConstants;

import java.io.IOException;
import java.io.InputStream;

public class PolarisDTOs {

    private final JsonMaker jsonMaker;

    public PolarisDTOs(JsonMaker jsonMaker) {
        this.jsonMaker = jsonMaker;
    }

    public String catalogAdminGrants() throws IncorrectConfigException {
        return toJson(
            new CatalogGrant(CatalogPrivilege.TABLE_WRITE_DATA, GrantResource.TypeEnum.CATALOG)
                     );
    }

    public String principalCreation(String principalName) throws IncorrectConfigException {
        return toJson(
            Principal.builder().setClientId(principalName).setName(principalName).build()
                     );
    }

    public String principalRoleCreation(String principalRole) throws IncorrectConfigException {
        return toJson(
            PrincipalRole.builder().setName(principalRole).build()
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

    protected String toJson(Object object) throws IncorrectConfigException {
        try {
            return jsonMaker.toJson(object);
        } catch (JsonProcessingException e) {
            throw new IncorrectConfigException(this.toString(), e);
        }
    }

    public <T> T fromJson(InputStream is, Class<T> clazz) throws IOException {
        return jsonMaker.fromJson(is, clazz);
    }

}
