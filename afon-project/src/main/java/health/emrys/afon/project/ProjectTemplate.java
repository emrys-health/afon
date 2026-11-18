package health.emrys.afon.project;

import health.emrys.afon.config.AfonCloud;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The templates of a {@value FileProjectProvider#FILE}, one for each role, {@code project-<role>.yaml} in the {@code templates/project} dir of the
 * Afon distribution, which the deployer completes.  What is in {@code <angle brackets>} is what only the deployer knows, so the file
 * is not valid until it is replaced.  The templates are written once, for every cloud: {@code ${id}}, {@code ${cloud}}, {@code ${CLOUD}},
 * {@code ${env}} and {@code ${nodePool}}, the cluster's nodes, are replaced.  A variable is a whole value, never a section, so
 * that a template is valid YAML as it is written, as a plain value, and an editor can check it.
 */
public final class ProjectTemplate {

    /** Where the templates are in the Afon distribution, from its dir. */
    public static final String DIR = "templates/project";

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z]+)}");

    private ProjectTemplate() {
    }

    /**
     * @param dir  the dir of the templates
     * @param env  the env whose config the project is made from, or {@code null} to leave it for the deployer
     * @return the project file for the project, from the template of the role, to complete
     * @throws IllegalArgumentException if the template of the role is not there, or has a variable that is not known
     */
    public static String render(@NonNull Path dir, @NonNull ProjectRole role, @NonNull AfonCloud cloud, @NonNull String id, @Nullable String env) {
        Path file = dir.resolve("project-" + role.value() + ".yaml");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No template " + file);
        }
        String template;
        try {
            template = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the template " + file, e);
        }
        Matcher variables = VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (variables.find()) {
            String value = switch (variables.group(1)) {
                case "id" -> id;
                case "cloud" -> cloud.toString().toLowerCase(Locale.ROOT);
                case "CLOUD" -> cloud.name();
                case "env" -> env == null ? "<env>" : env;
                case "nodePool" -> nodePool(role, cloud);
                default -> throw new IllegalArgumentException("The template " + file + " has the variable " + variables.group(0) + ", which is not known");
            };
            variables.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        variables.appendTail(result);
        return result.toString();
    }

    /**
     * @return the node pool of the cluster, as a flow mapping: what the role needs, the identity running Keycloak only and a Data Provider Polaris only, or empty on a cloud with
     * no node pool property
     */
    static String nodePool(ProjectRole role, AfonCloud cloud) {
        // the identity runs Keycloak only, and a Data Provider Polaris only, so they take the smaller nodes
        boolean identity = role != ProjectRole.TRE;
        return switch (cloud) {
            case AZURE -> pool("vmSize", identity ? "Standard_D2s_v3" : "Standard_D4s_v6", 2);
            case GCP -> pool("machineType", identity ? "e2-standard-2" : "e2-standard-4", 2);
            case AWS -> "{}";
        };
    }

    private static String pool(String sizeKey, String size, int count) {
        return "{" + sizeKey + ": " + size + ", nodeCount: " + count + "}";
    }
}
