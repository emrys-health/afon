package health.emrys.afon.platform;

import health.emrys.afon.config.AfonProperties.SupportedCloudsEnum;

import java.util.Arrays;

/**
 * Migration shims. {@code SupportedCloudsEnum} survives as an internal detail
 * of the legacy path until Phase 4; new-code call sites go through here so
 * they are trivial to find and delete.
 */
final class Legacy {

    private Legacy() {
    }

    static SupportedCloudsEnum enumFor(String cloudId) {
        return Arrays.stream(SupportedCloudsEnum.values())
                     .filter(e -> e.toString().equals(cloudId))
                     .findFirst()
                     .orElseThrow(() -> new IllegalArgumentException(
                         "Cloud id '" + cloudId + "' has no SupportedCloudsEnum equivalent; "
                         + "arbitrary cloud ids arrive with the Phase 3 planner"));
    }
}
