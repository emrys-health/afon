package health.emrys.afon.project;

/**
 * Provides the project stack that Afon deploys, as the deployer defines it, in the way that {@link health.emrys.afon.config.AfonUserProvider}
 * provides the users and {@link health.emrys.afon.config.AfonPropertyProvider} the properties.
 */
public interface ProjectStackProvider {

    /**
     * @return the project stack
     * @throws ProjectStackException      if the project stack is not valid
     * @throws IllegalStateException if it cannot be read
     */
    ProjectStack getProjectStack();
}
