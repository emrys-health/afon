package health.emrys.afon.cdk;

import health.emrys.afon.security.keycloak.OnboardingConfig;

import java.util.List;

public class Onboarding implements OnboardingConfig {

    /**
     * Fixed roster. Guacamole's file-based auth and the per-user VNC Services must enumerate users
     * up front, so adding a user means editing this list and redeploying.
     * TODO - read from a CSV file
     */
    private final List<EmrysUser> users = List.of(
        new EmrysUser("phil", "phil.henry@emrys.health", "phil-henry"),
        new EmrysUser("will", "will@emrys.health", "will"));


    public List<EmrysUser> getUsers() {
        return users;
    }

}
