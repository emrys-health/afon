package health.emrys.afon.cdk.configs;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
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
 * the config dir ({@link ConfigUtils#configDir()}).  The file is not mandatory yet: if it is missing, the deprecated {@code ALLOWED_DOMAINS_CSV} is used as the
 * common domains, or no domains are allowed if that is not set either.  It has a {@code common} section, for the domains of any cloud, and a section per cloud ({@code aws},
 * {@code azure}, {@code gcp}), each with:
 * <UL>
 * <LI>{@code domains}: the domain names, spelled out as RFC 1123 host names (so no wildcards)</LI>
 * <LI>{@code catalogs}: templates expanded once for each catalog of the cloud that has a bucket, eg the bucket's endpoint</LI>
 * </UL>
 * as in
 * <pre>
 * common:
 *   domains:
 *     - pypi.org
 * aws:
 *   domains:
 *     - ${OIDC_DOMAIN_NAME}
 *     - ${POLARIS_DOMAIN_NAME}
 *   catalogs:
 *     - ${BUCKET_NAME}.s3.${AWS_REGION}.amazonaws.com
 * </pre>
 * A {@code ${NAME}} is replaced by the value of the property of that name, except that in the section of a cloud, a
 * {@link AfonCloudProperty cloud-agnostic name} is that of the cloud's own property, eg {@code ${POLARIS_DOMAIN_NAME}} is
 * {@code AWS_POLARIS_DOMAIN_NAME} in {@code aws}, and {@code ${OIDC_DOMAIN_NAME}} is the cloud's own OIDC domain if it has
 * one, else the global one, see {@link AfonPropertyProvider#getOidcDomainName(AfonCloud)}.  In {@code catalogs},
 * {@code ${BUCKET_NAME}} is the catalog's bucket.  An entry that refers to a property that is not set or blank is left out,
 * with a warning, so that a cloud that is not deployed to needs none of its properties.
 */
public class WhitelistConfig {

    /**
     * The file in the config dir with the whitelist.
     */
    public static final String WHITELIST_FILE = "whitelist.yaml";

    private static final Logger logger = getLogger(WhitelistConfig.class);

    private static final String COMMON = "common";
    private static final String DOMAINS = "domains";
    private static final String CATALOGS = "catalogs";
    private static final String BUCKET_NAME = "BUCKET_NAME";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");

    private final AfonPropertyProvider properties;
    private final Map<String, Section> sections;

    private record Section(List<String> domains, List<String> catalogs) {
    }

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
    private Map<String, Section> fallback(Path file) {
        List<String> csv = java.util.Arrays.stream(properties.getValue(AfonProperty.ALLOWED_DOMAINS_CSV, "").split(","))
                                           .map(String::trim).filter(domain -> !domain.isEmpty()).toList();
        if (csv.isEmpty()) {
            logger.warn("{} not found and ALLOWED_DOMAINS_CSV not set: no domains are allowed, copy it from templates/config", file);
            return Map.of();
        }
        logger.warn("{} not found: using the deprecated ALLOWED_DOMAINS_CSV, for all clouds", file);
        return Map.of(COMMON, new Section(csv, List.of()));
    }

    /**
     * @return the domains of all the sections: the common ones and those of every cloud
     */
    public List<String> domains() {
        Set<String> domains = new LinkedHashSet<>(commonDomains());
        for (AfonCloud cloud : AfonCloud.values()) {
            domains.addAll(cloudDomains(cloud));
        }
        return List.copyOf(domains);
    }

    /**
     * @return the common domains and those of the cloud only
     */
    public List<String> domains(AfonCloud cloud) {
        Set<String> domains = new LinkedHashSet<>(commonDomains());
        domains.addAll(cloudDomains(cloud));
        return List.copyOf(domains);
    }

    private List<String> commonDomains() {
        return resolve(COMMON, section(COMMON).domains(), null);
    }

    private List<String> cloudDomains(AfonCloud cloud) {
        Section section = section(cloud.toString());
        List<String> domains = new ArrayList<>(resolve(cloud.toString(), section.domains(), cloud));
        if (!section.catalogs().isEmpty()) {
            for (AfonCatalog catalog : new AfonCatalogs(properties).all()) {
                if (catalog.cloud() == cloud && catalog.bucketName() != null && !catalog.bucketName().isBlank()) {
                    domains.addAll(resolve(cloud + " catalog " + catalog.id(), section.catalogs(), cloud,
                                           Map.of(BUCKET_NAME, catalog.bucketName().trim())));
                }
            }
        }
        return domains;
    }

    private Section section(String name) {
        return sections.getOrDefault(name, new Section(List.of(), List.of()));
    }

    private List<String> resolve(String where, List<String> templates, AfonCloud cloud) {
        return resolve(where, templates, cloud, Map.of());
    }

    private List<String> resolve(String where, List<String> templates, AfonCloud cloud, Map<String, String> extra) {
        List<String> resolved = new ArrayList<>();
        for (String template : templates) {
            String domain = expand(template, cloud, extra);
            if (domain == null || domain.isBlank()) {
                logger.warn("Leaving out whitelist entry '{}' of {}: it refers to a property that is not set", template, where);
            } else {
                resolved.add(domain.trim());
            }
        }
        return resolved;
    }

    /**
     * @return the template with its placeholders replaced, or {@code null} if one has no value
     */
    private String expand(String template, AfonCloud cloud, Map<String, String> extra) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String value = value(matcher.group(1), cloud, extra);
            if (value == null || value.isBlank()) {
                return null;
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value.trim()));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private String value(String name, AfonCloud cloud, Map<String, String> extra) {
        if (extra.containsKey(name)) {
            return extra.get(name);
        }
        if (cloud != null) {
            for (AfonCloudProperty cloudProperty : AfonCloudProperty.values()) {
                if (cloudProperty.name().equals(name)) {
                    if (cloudProperty == AfonCloudProperty.OIDC_DOMAIN_NAME) {
                        return properties.getValue(properties.getOidcDomainProperty(cloud), "");
                    }
                    return properties.getCloudValue(cloud, cloudProperty, "");
                }
            }
        }
        return properties.getProperties().getProperty(name);
    }

    /**
     * @param yaml   the content of the whitelist file
     * @param source the file's name, for messages
     * @throws IllegalStateException if the whitelist is not defined correctly
     */
    static Map<String, Section> parse(String yaml, String source) {
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
        Map<String, Section> sections = new java.util.HashMap<>();
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

    private static Section section(Object value, String name, String source) {
        if (value == null) {
            return new Section(List.of(), List.of());
        }
        Map<String, Object> map = asMap(value, source, "section '" + name + "'");
        for (String key : map.keySet()) {
            if (!key.equals(DOMAINS) && !(key.equals(CATALOGS) && !name.equals(COMMON))) {
                throw new IllegalStateException(
                    "Invalid whitelist " + source + ": unknown key '" + key + "' in section '" + name + "'");
            }
        }
        return new Section(strings(map.get(DOMAINS), name + "." + DOMAINS, source),
                           strings(map.get(CATALOGS), name + "." + CATALOGS, source));
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> asMap(Object value, String source, String where) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException("Invalid whitelist " + source + ": " + where + " must be a mapping");
        }
        return (Map<String, Object>) value;
    }

    private static List<String> strings(Object value, String where, String source) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Invalid whitelist " + source + ": " + where + " must be a list");
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String string) || string.isBlank()) {
                throw new IllegalStateException(
                    "Invalid whitelist " + source + ": " + where + " must only have domain names, not '" + item + "'");
            }
            if (string.contains("*")) {
                throw new IllegalStateException(
                    "Invalid whitelist " + source + ": " + where + " must not have wildcards, spell out '" + string + "'");
            }
            strings.add(string.trim());
        }
        return strings;
    }
}
