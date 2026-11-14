package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * The references to deploy the Docker images of {@link DockerImages} by, for one cloud.
 * <p>
 * Without {@code <CLOUD>_IMAGE_REGISTRY} an image is referenced as {@code docker-images.yaml} has it.  With it, an image that is not first-party
 * is referenced in the registry, at the same tag and digest, where {@code docker-image --sign} copied and signed it, so that the
 * signature is found next to the image that the pod pulls, eg {@code quay.io/keycloak/keycloak:26.6.3@sha256:...} is
 * {@code <registry>/keycloak/keycloak:26.6.3@sha256:...}.  The images that stay as they are, with a registry, are the first-party ones, which
 * are signed where they are, and those with no digest, which cannot be mirrored or signed: those are logged, so that it is known they
 * will not be verified.  Which they are, and where the copy is, is decided by {@link DockerImageDefinitions.Definition#reference(String)},
 * the same as for signing.
 */
public final class DockerImageResolver {

    private static final Logger logger = getLogger(DockerImageResolver.class);

    private final Map<String, DockerImageDefinitions.Definition> images;
    private final String                                         registry;
    private final Set<String>                                    warned = ConcurrentHashMap.newKeySet();

    /**
     * @param images   the images by name
     * @param registry the registry, with the path in it, that the images are kept in, or blank for the images as they are defined
     */
    public DockerImageResolver(Map<String, DockerImageDefinitions.Definition> images, String registry) {
        this.images   = images;
        this.registry = registry == null ? "" : registry.trim();
    }

    /**
     * @return the resolver of the images of {@link DockerImages} for the cloud, with the registry that its properties give, if any
     */
    public static DockerImageResolver of(AfonPropertyProvider properties, AfonCloud cloud) {
        return new DockerImageResolver(DockerImages.all(), properties.getCloudValue(cloud, AfonCloudProperty.IMAGE_REGISTRY, ""));
    }

    /**
     * @return the resolver of the images of {@link DockerImages} as {@code docker-images.yaml} has them, as for a cloud with no registry
     */
    public static DockerImageResolver asDefined() {
        return new DockerImageResolver(DockerImages.all(), "");
    }

    /**
     * @return the registry the images are kept in, or blank if they are referenced as defined
     */
    public String registry() {
        return registry;
    }

    /**
     * @param name the name of the image, as in {@code docker-images.yaml}
     * @return the reference to pull the image by
     * @throws IllegalStateException if there is no such image
     */
    public String reference(String name) {
        return lookup(name).reference(registry);
    }

    /**
     * For the image that a Helm chart pulls from a repository of its own, of which only the tag is given: the tag is that of the image as
     * defined, whichever registry the cloud has, and an image that is left out of the registry is logged as by {@link #reference(String)}.
     *
     * @param name the name of the image, as in {@code docker-images.yaml}
     * @return the tag of the image
     * @throws IllegalStateException if there is no such image
     */
    public String tag(String name) {
        return lookup(name).tag();
    }

    private DockerImageDefinitions.Definition lookup(String name) {
        DockerImageDefinitions.Definition image = images.get(name);
        if (image == null) {
            throw new IllegalStateException(String.format("No Docker image '%s' defined in %s, expected one of %s",
                                                          name, DockerImageDefinitions.IMAGES_FILE, images.keySet()));
        }
        if (!registry.isBlank() && !image.isMirrored(registry) && !DockerImageDefinitions.FIRST_PARTY.equals(image.category())
            && warned.add(name)) {
            logger.warn("{} is not in the image registry {}, as it has no digest to sign it at: it is pulled from {}, and not verified",
                        name, registry, image.image());
        }
        return image;
    }

    public String polaris()          { return reference("polaris"); }
    public String polarisAdminTool() { return reference("polaris-admin-tool"); }
    public String keycloak()         { return reference("keycloak"); }
    public String spark()            { return reference("spark"); }
    public String pysparkNotebook()  { return reference("pyspark-notebook"); }
    public String gitea()            { return reference("gitea"); }
    public String jupyterhubHub()    { return reference("jupyterhub-hub"); }
    public String jupyterhubProxy()  { return reference("jupyterhub-proxy"); }
    public String guacamole()        { return reference("guacamole"); }
    public String guacd()            { return reference("guacd"); }
    public String oauth2Proxy()      { return reference("oauth2-proxy"); }
    public String curl()             { return reference("curl"); }
    public String postgresAlpine()   { return reference("postgres-alpine"); }
}
