package health.emrys.afon.cdk;

import health.emrys.afon.security.keycloak.OnboardingConfig;

import java.util.List;
import java.util.stream.Collectors;

public class Onboarding implements OnboardingConfig {

    /**
     * Fixed roster. Guacamole's file-based auth and the per-user VNC Services must enumerate users
     * up front, so adding a user means editing this list and redeploying.
     * TODO - read from a CSV file
     */
    private final List<EmrysUser> users = List.of(
        // phil.henry@emrys.health already used by root so without the trailing 'x', this will error with:
        // Error = Could not create KeyCloak user 'phil' in realm 'polaris': HTTP 409 but no user with that
        // username exists. The email 'phil.henry@emrys.health' is most likely already held by another user
        // (realm duplicateEmailsAllowed=false).
        // Keep it lower case: KeyCloak lower-cases e-mails, and EmrysUser now normalises to match, but an
        // upper-case roster entry reads as if the two could differ. They cannot — the 'x' alone disambiguates.
        new EmrysUser("phil", "phil.henryx@emrys.health", "phil-henry", "s3cr3t", "phillip", "henry"),
        new EmrysUser("will", "will@emrys.health", "will", "s3cr3t", "william", "browne"));


    public List<EmrysUser> getUsers() {
        return users;
    }

    @Override
    public String toString() {
        return getUsers().stream().map(x -> x.userName()).collect(Collectors.joining("\n"));
    }
}
