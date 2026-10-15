package health.emrys.afon.config;

import org.jspecify.annotations.NonNull;

/**
 * Identifies a stack as {@code <cloud>-<stack-type>}, named as {@code validate-stack}
 * names the stacks.  Each stack has its own plan dir and tofu state, see {@link ConfigUtils#stackDir(StackId)}
 * and {@link ConfigUtils#tfStatePath(StackId)}.
 * <p>
 * A stack id is not a stack <em>name</em>, which is the {@code String} naming a stack dir or tofu state found in the cdktf dir,
 * as {@code StepActions} and {@code StackFilter} take them.  A name is usually the text of a stack id, but may also be that of
 * a cloud, eg {@code aws}, for the legacy stack that all the stacks of the cloud shared before they had their own state, or
 * of a stack that the stacks file no longer defines, whose resources are unknown.  So a name is parsed into a stack id only
 * once it is known to be neither.
 */
public record StackId(@NonNull AfonCloud cloud, @NonNull String type) implements Comparable<StackId> {

    /**
     * The type standing for every stack of a cloud, {@code <cloud>-all}, which is not a stack itself.
     */
    public static final String ALL_TYPE = "all";

    /**
     * Parses a stack id, case-insensitively.
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

    /**
     * @return the id standing for every stack of the cloud, {@code <cloud>-all}
     */
    public static StackId all(AfonCloud cloud) {
        return new StackId(cloud, ALL_TYPE);
    }

    /**
     * @return whether the id stands for every stack of its cloud, {@code <cloud>-all}
     */
    public boolean isAll() {
        return ALL_TYPE.equals(type);
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
