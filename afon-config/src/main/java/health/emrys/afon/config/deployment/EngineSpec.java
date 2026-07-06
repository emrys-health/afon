package health.emrys.afon.config.deployment;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A query engine selection. {@code spark} and {@code trino} deploy onto the
 * Kubernetes cluster of the {@code target} cloud; {@code snowflake} is SaaS
 * and instead requires {@code jdbcUrlRef} (a property name, never the URL
 * itself) plus the catalogs it should read via catalog integrations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EngineSpec(
    String type,
    String target,
    Integer workers,
    String jdbcUrlRef,
    List<String> readsCatalogs) {

    public static final String TYPE_SPARK     = "spark";
    public static final String TYPE_TRINO     = "trino";
    public static final String TYPE_SNOWFLAKE = "snowflake";

    @JsonIgnore
    public boolean isSnowflake() {
        return TYPE_SNOWFLAKE.equals(type);
    }
}
