package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * The domain names that the TRE's notebooks, Spark master and Spark driver may resolve, read from {@code whitelist.yaml} in
 * the config dir ({@link ConfigUtils#configDir()}).  The file is not mandatory yet: if it is missing, the deprecated
 * {@code ALLOWED_DOMAINS_CSV} is used as the common domains, or no domains are allowed if that is not set either.  The file has a
 * {@code common} section, for the domains of any cloud, and a section per cloud ({@code aws}, {@code azure}, {@code gcp}),
 * each with a list of {@code domains}, spelled out as RFC 1123 host names (so no wildcards), as in
 * <pre>
 * common:
 *   domains:
 *     - ${OIDC_DOMAIN_NAME}
 * aws:
 *   domains:
 *     - ${AWS_POLARIS_DOMAIN_NAME}
 *     - ${AWS_BUCKET_NAME_aws}.s3.${AWS_REGION}.amazonaws.com
 * </pre>
 * A {@code ${NAME}} is replaced by the value of the property of that name, which is either an {@link AfonProperty} or the
 * key of a catalog, {@code <CLOUD>_BUCKET_NAME_<id>}, {@code <CLOUD>_CATALOG_NAME_<id>}, {@code <CLOUD>_CATALOG_TYPE_<id>} or
 * {@code <CLOUD>_ADMIN_GRANTS_<id>}, see {@link health.emrys.afon.config.AfonCatalogs}.  Any other name fails the load, so
 * a typo cannot silently drop a domain.  An entry that refers to a property that is not set or blank is left out, with a
 * warning, so that a cloud that is not deployed to needs none of its properties.
 */
public class WhitelistConfig {

    /**
     * The file in the config dir with the whitelist.
     */
    public static final String WHITELIST_FILE = ConfigUtils.WHITELIST_FILE;

    private static final Logger logger = getLogger(WhitelistConfig.class);

    private static final String COMMON  = "common";
    private static final String DOMAINS = "domains";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");

    /**
     * The keys of a catalog, which are not {@link AfonProperty}s as they depend on the catalog ids.
     */
    private static final Pattern CATALOG_KEY = Pattern.compile("^(AWS|AZURE|GCP)_(BUCKET_NAME|CATALOG_NAME|CATALOG_TYPE|ADMIN_GRANTS)_.+");

    private final AfonPropertyProvider properties;
    private final Map<String, List<String>> sections;

    public WhitelistConfig(AfonPropertyProvider properties) {
        this(properties, ConfigUtils.configDir());
    }

    public WhitelistConfig(AfonPropertyProvider properties, String configDir) {
        this.properties = properties;
        Path file = Path.of(configDir, WHITELIST_FILE);
        if (!Files.isRegularFile(file)) {
            this.sections = fallback(file);
            return;
        }
        try {
            this.sections = parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read whitelist " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * @return the domains of the deprecated {@code ALLOWED_DOMAINS_CSV} as the common ones, or none if it is not set either
     */
    private Map<String, List<String>> fallback(Path file) {
        List<String> csv = Arrays.stream(properties.getValue(AfonProperty.ALLOWED_DOMAINS_CSV, "").split(","))
                                 .map(String::trim).filter(domain -> !domain.isEmpty()).toList();
        if (csv.isEmpty()) {
            logger.warn("{} not found and ALLOWED_DOMAINS_CSV not set: no domains are allowed, copy it from templates/config", file);
            return Map.of();
        }
        logger.warn("{} not found: using the deprecated ALLOWED_DOMAINS_CSV, for all clouds", file);
        return Map.of(COMMON, csv);
    }

    /**
     * @return the domains of all the sections: the common ones and those of every cloud
     */
    public List<String> domains() {
        Set<String> domains = new LinkedHashSet<>(resolve(COMMON));
        for (AfonCloud cloud : AfonCloud.values()) {
            domains.addAll(resolve(cloud.toString()));
        }
        return List.copyOf(domains);
    }

    /**
     * @return the common domains and those of the cloud only
     */
    public List<String> domains(AfonCloud cloud) {
        Set<String> domains = new LinkedHashSet<>(resolve(COMMON));
        domains.addAll(resolve(cloud.toString()));
        return List.copyOf(domains);
    }

    private List<String> resolve(String section) {
        List<String> resolved = new ArrayList<>();
        for (String template : sections.getOrDefault(section, List.of())) {
            String domain = expand(template);
            if (domain == null || domain.isBlank()) {
                logger.warn("Leaving out whitelist entry '{}' of {}: it refers to a property that is not set", template, section);
            } else {
                resolved.add(domain.trim());
            }
        }
        return resolved;
    }

    /**
     * @return the template with its placeholders replaced, or {@code null} if one has no value
     */
    private String expand(String template) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String value = properties.getProperties().getProperty(matcher.group(1));
            if (value == null || value.isBlank()) {
                return null;
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value.trim()));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    /**
     * @param yaml   the content of the whitelist file
     * @param source the file's name, for messages
     * @return the templates of the domains of each section
     * @throws IllegalStateException if the whitelist is not defined correctly
     */
    static Map<String, List<String>> parse(String yaml, String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root;
        try {
            root = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid whitelist " + source + ": " + e.getMessage(), e);
        }
        if (root == null) {
            return Map.of();
        }
        Set<String> allowed = new LinkedHashSet<>(List.of(COMMON));
        for (AfonCloud cloud : AfonCloud.values()) {
            allowed.add(cloud.toString());
        }
        Map<String, List<String>> sections = new HashMap<>();
        for (Map.Entry<String, Object> entry : asMap(root, source, "the file").entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (!allowed.contains(name)) {
                throw new IllegalStateException(
                    "Invalid whitelist " + source + ": unknown section '" + entry.getKey() + "', expected one of " + allowed);
            }
            sections.put(name, section(entry.getValue(), name, source));
        }
        return sections;
    }

    private static List<String> section(Object value, String name, String source) {
        if (value == null) {
            return List.of();
        }
        Map<String, Object> map = asMap(value, source, "section '" + name + "'");
        for (String key : map.keySet()) {
            if (!key.equals(DOMAINS)) {
                throw new IllegalStateException(
                    "Invalid whitelist " + source + ": unknown key '" + key + "' in section '" + name + "'");
            }
        }
        return domains(map.get(DOMAINS), name + "." + DOMAINS, source);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> asMap(Object value, String source, String where) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException("Invalid whitelist " + source + ": " + where + " must be a mapping");
        }
        return (Map<String, Object>) value;
    }

    private static List<String> domains(Object value, String where, String source) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Invalid whitelist " + source + ": " + where + " must be a list");
        }
        List<String> domains = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String string) || string.isBlank()) {
                throw new IllegalStateException(
                    "Invalid whitelist " + source + ": " + where + " must only have domain names, not '" + item + "'");
            }
            if (string.contains("*")) {
                throw new IllegalStateException(
                    "Invalid whitelist " + source + ": " + where + " must not have wildcards, spell out '" + string + "'");
            }
            checkPlaceholders(string, where, source);
            domains.add(string.trim());
        }
        return domains;
    }

    /**
     * @throws IllegalStateException if a {@code ${NAME}} of the entry is neither an {@link AfonProperty} nor a catalog key
     */
    private static void checkPlaceholders(String entry, String where, String source) {
        Matcher matcher = PLACEHOLDER.matcher(entry);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!AfonProperty.getAllNames().contains(name) && !CATALOG_KEY.matcher(name).matches()) {
                throw new IllegalStateException("Invalid whitelist " + source + ": " + where + " refers to '" + name
                                                + "' in '" + entry + "', which is neither an AfonProperty nor a catalog key");
            }
        }
    }
}
