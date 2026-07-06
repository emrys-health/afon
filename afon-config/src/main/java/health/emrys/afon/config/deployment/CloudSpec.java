package health.emrys.afon.config.deployment;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * One cloud instance hosting data. The list of clouds is open-ended: the same
 * {@code provider} may appear any number of times (e.g. two AWS accounts),
 * distinguished by {@code id}, which also becomes the CDKTF stack ID.
 * <p>
 * {@code storage} and {@code polaris} hold provider-specific keys (e.g.
 * {@code bucket}/{@code roleArn} for aws, {@code storageAccount}/{@code container}
 * for azure); the provider plugin interprets them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudSpec(
    String id,
    String provider,
    String region,
    Map<String, String> storage,
    Map<String, String> polaris) {

    public static final String PROVIDER_AWS   = "aws";
    public static final String PROVIDER_AZURE = "azure";
    public static final String PROVIDER_GCP   = "gcp";
}
