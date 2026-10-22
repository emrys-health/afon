package health.emrys.afon.ops;

import health.emrys.afon.cdk.DockerImageDefinitions;
import health.emrys.afon.cdk.DockerImages;
import health.emrys.afon.config.ConfigUtils;

import java.nio.file.Path;
import java.util.List;

/**
 * Prints the reference of a Docker image of {@code docker-images.yaml}, for a shell script that needs one, as
 * {@code AFON_DOCKER_IMAGE=<image>:<tag>[@<digest>]}.  Usage: {@code docker-image <name>}, eg {@code docker-image postgres-alpine}.
 * {@code docker-image --export} writes the bundled images file to the config dir instead, to edit it.
 */
public class DockerImageMain {

    /**
     * The name of the line printed.
     */
    static final String VARIABLE = "AFON_DOCKER_IMAGE";

    static final String EXPORT_OPTION = "--export";

    /**
     * @return the line to print
     * @throws IllegalArgumentException if the args are not as above, or the image is not one of the images file
     */
    static List<String> lines(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: docker-image <name|" + EXPORT_OPTION + ">, one of " + DockerImages.all().keySet());
        }
        var image = DockerImages.all().get(args[0]);
        if (image == null) {
            throw new IllegalArgumentException("Unknown image '" + args[0] + "', expected one of " + DockerImages.all().keySet());
        }
        return List.of(VARIABLE + "=" + image.reference());
    }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && EXPORT_OPTION.equals(args[0])) {
                Path file = DockerImageDefinitions.exportBundled(Path.of(ConfigUtils.configDir()));
                System.out.printf("Exported the bundled Docker images to %s, which now replaces them: edit it to define the images differently%n", file);
                return;
            }
            lines(args).forEach(System.out::println);
        } catch (IllegalArgumentException | IllegalStateException | ExceptionInInitializerError e) {
            System.err.println(e.getMessage() != null ? e.getMessage() : String.valueOf(e.getCause()));
            System.exit(1);
        }
    }
}
