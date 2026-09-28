package health.emrys.afon.config;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An Afon user ACL.
 *
 * The catalogs are a colon-separated list of entries, each one of:
 * - `all`: every catalog
 * - a catalog name, e.g. `aws`
 * - an exclusion, a catalog name prefixed by `!`, e.g. `!aws_forbidden`
 * Names may use the `*` wildcard, e.g. `aws_*` or `!*_forbidden`.
 *
 * A catalog is authorised if it matches an inclusion and no exclusion: exclusions win over inclusions,
 * so an ACL of only exclusions authorises no catalog.
 */
public record AfonUserAcl(String username,
                          String catalogs) {

    public static final String ALL_CATALOGS = "all";
    public static final String UNDEFINED = "undefined";
    public static final String EXCLUSION = "!";
    public static final String WILDCARD = "*";

    public AfonUserAcl {
    }

    public boolean isAuthorised(String catalogName) {
        if ( catalogs.equals(UNDEFINED) ) return true;
        List<String> entries = Arrays.stream(catalogs.split(":"))
                .map(String::trim).filter(entry -> !entry.isEmpty()).toList();
        boolean excluded = entries.stream()
                .filter(entry -> entry.startsWith(EXCLUSION))
                .anyMatch(entry -> matches(entry.substring(EXCLUSION.length()), catalogName));
        if ( excluded ) return false;
        return entries.stream()
                .filter(entry -> !entry.startsWith(EXCLUSION))
                .anyMatch(entry -> entry.equals(ALL_CATALOGS) || matches(entry, catalogName));
    }

    private static boolean matches(String name, String catalogName) {
        if ( !name.contains(WILDCARD) ) return name.equals(catalogName);
        String regex = Arrays.stream(name.split(Pattern.quote(WILDCARD), -1))
                .map(Pattern::quote).collect(Collectors.joining(".*"));
        return catalogName.matches(regex);
    }
}
