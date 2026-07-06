package health.emrys.afon.config.deployment;

import java.util.List;

/**
 * Thrown when a deployment configuration fails schema or semantic validation.
 * All problems are collected before throwing so a user can fix them in one pass.
 */
public class ConfigValidationException extends RuntimeException {

    private final List<String> errors;

    public ConfigValidationException(List<String> errors) {
        super("Invalid deployment configuration:\n - " + String.join("\n - ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
