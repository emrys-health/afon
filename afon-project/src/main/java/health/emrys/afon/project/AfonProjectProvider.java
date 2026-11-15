package health.emrys.afon.project;

/**
 * Provides the project that Afon deploys, as the deployer defines it, in the way that {@link health.emrys.afon.config.AfonUserProvider}
 * provides the users and {@link health.emrys.afon.config.AfonPropertyProvider} the properties.
 */
public interface AfonProjectProvider {

    /**
     * @return the project
     * @throws ProjectException      if the project is not valid
     * @throws IllegalStateException if it cannot be read
     */
    AfonProject getProject();
}
