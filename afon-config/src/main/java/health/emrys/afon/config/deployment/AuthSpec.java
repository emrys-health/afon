package health.emrys.afon.config.deployment;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Auth-proxy settings for the workbench. The {@code *Ref} fields name
 * properties in the secrets file; values are never inlined in the config.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthSpec(
    String provider,
    String emailDomain,
    String clientIdRef,
    String clientSecretRef,
    String cookieSecretRef) {
}
