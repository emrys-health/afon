package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;
import org.jspecify.annotations.NonNull;

/**
 * Identifies a stack as {@code <cloud>-<stack-type>}, eg {@code aws-keycloak}, named as {@code validate-stack}
 * names the stacks.
 */
public record StackId(@NonNull AfonCloud cloud, @NonNull String type) implements Comparable<StackId> {

    /**
     * Parses a stack id, case-insensitively, eg {@code aws-keycloak}.
     *
     * @throws IllegalArgumentException if it isn't {@code <cloud>-<stack-type>}, with a known cloud
     */
    public static StackId parse(String stackId) {
        int separator = stackId.indexOf('-');
        if (separator <= 0 || separator == stackId.length() - 1) {
            throw new IllegalArgumentException(
                String.format("Stack id '%s' is not <cloud>-<stack-type>, eg aws-keycloak", stackId));
        }
        String cloud = stackId.substring(0, separator);
        try {
            return new StackId(AfonCloud.valueOf(cloud.toUpperCase()), stackId.substring(separator + 1).toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Stack id '%s' has an unknown cloud '%s'", stackId, cloud));
        }
    }

    @Override
    public int compareTo(StackId other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
        return cloud + "-" + type;
    }

}
