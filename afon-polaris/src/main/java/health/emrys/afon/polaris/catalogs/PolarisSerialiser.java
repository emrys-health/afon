package health.emrys.afon.polaris.catalogs;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.polaris.core.admin.model.*;
import org.apache.polaris.core.entity.PolarisEntityConstants;

import java.io.IOException;
import java.io.InputStream;

public class PolarisSerialiser {

    private final JsonSerialiser jsonSerialiser;

    public PolarisSerialiser(){
        this(new JsonSerialiser());
    }

    public PolarisSerialiser(JsonSerialiser jsonSerialiser) {
        this.jsonSerialiser = jsonSerialiser;
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

    /**
     * @return the request that grants the privilege on the namespace to a catalog role, which holds for what is in the namespace too
     */
    public String namespaceGrant(java.util.List<String> namespace, NamespacePrivilege privilege) throws IncorrectConfigException {
        return toJson(AddGrantRequest.builder().setGrant(namespaceGrantResource(namespace, privilege)).build());
    }

    /**
     * @return the request that takes the privilege on the namespace away from a catalog role
     */
    public String namespaceRevoke(java.util.List<String> namespace, NamespacePrivilege privilege) throws IncorrectConfigException {
        return toJson(RevokeGrantRequest.builder().setGrant(namespaceGrantResource(namespace, privilege)).build());
    }

    private static GrantResource namespaceGrantResource(java.util.List<String> namespace, NamespacePrivilege privilege) {
        return NamespaceGrant.builder().setNamespace(namespace).setPrivilege(privilege).setType(GrantResource.TypeEnum.NAMESPACE).build();
    }

    /**
     * @return the Iceberg REST request that makes a namespace
     */
    public String namespaceCreation(java.util.List<String> namespace) throws IncorrectConfigException {
        return toJson(java.util.Map.of("namespace", namespace, "properties", java.util.Map.of()));
    }

    /**
     * @return the Iceberg REST request that moves a table from a namespace to another of the same catalog
     */
    public String tableRename(java.util.List<String> from, String table, java.util.List<String> to) throws IncorrectConfigException {
        return toJson(java.util.Map.of("source", java.util.Map.of("namespace", from, "name", table),
                                       "destination", java.util.Map.of("namespace", to, "name", table)));
    }

    public String catalogRoles() throws IncorrectConfigException {
        return toJson(
            new CatalogRole(PolarisEntityConstants.getNameOfCatalogAdminRole())
                     );
    }

    public String toJson(Object object) throws IncorrectConfigException {
        try {
            return jsonSerialiser.toJson(object);
        } catch (JsonProcessingException e) {
            throw new IncorrectConfigException(this.toString(), e);
        }
    }

    public <T> T fromJson(InputStream is, Class<T> clazz) throws IOException {
        return jsonSerialiser.fromJson(is, clazz);
    }

}
