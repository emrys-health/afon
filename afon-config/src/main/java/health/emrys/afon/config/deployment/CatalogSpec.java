package health.emrys.afon.config.deployment;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A Polaris catalog, decoupled from the clouds themselves. Replaces the roles
 * previously conflated in {@code SupportedCloudsEnum}:
 * <ul>
 *   <li>{@code internal} — data lives in the referenced cloud's storage</li>
 *   <li>{@code federated} — the {@code via} cloud's Polaris proxies to the
 *       referenced cloud's Polaris (was {@code AZURE_EXTERNAL} etc.)</li>
 *   <li>{@code testFixture: true} — exists only for acceptance tests
 *       (was {@code *_FORBIDDEN})</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogSpec(
    String name,
    String cloud,
    String type,
    String via,
    String bucketOverride,
    Boolean testFixture) {

    public static final String TYPE_INTERNAL  = "internal";
    public static final String TYPE_FEDERATED = "federated";

    @JsonIgnore
    public boolean isFederated() {
        return TYPE_FEDERATED.equals(type);
    }
}
