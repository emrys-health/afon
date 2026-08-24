package health.emrys.afon.config;

import java.util.Arrays;

/**
 * An Afon user ACL.
 */
public record AfonUserAcl(String username,
                          String catalogs) {

    public static final String ALL_CATALOGS = "all";
    public static final String UNDEFINED = "undefined";

    public AfonUserAcl {
    }

    public boolean isAuthorised(String catalogName) {
         if ( catalogs.equals(ALL_CATALOGS) || catalogs.equals(UNDEFINED) ) return true;
         return Arrays.asList(catalogs.split(":")).contains(catalogName);
    }
}
