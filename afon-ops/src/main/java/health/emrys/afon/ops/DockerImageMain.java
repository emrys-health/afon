package health.emrys.afon.ops;

import health.emrys.afon.cdk.DockerImageDefinitions;
import health.emrys.afon.cdk.DockerImagePinner;
import health.emrys.afon.cdk.DockerImages;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.ops.reports.DockerImageReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints the reference of a Docker image of {@code docker-images.yaml}, for a shell script that needs one, as
 * {@code AFON_DOCKER_IMAGE=<image>:<tag>[@<digest>]}.  Usage: {@code docker-image <name>}, eg {@code docker-image postgres-alpine}.
 * {@code docker-image --export} writes the bundled images file to the config dir instead, to edit it, and
 * {@code docker-image --pin [--file <file>] [<name>...]} looks up the digests of its images in their registries and writes them to it,
 * and {@code docker-image --verify [--file <file>] [--report <file>] [<name>...]} only checks that they are those the tags point to, failing if not.
 */
public class DockerImageMain {

    /**
     * The name of the line printed.
     */
    static final String VARIABLE = "AFON_DOCKER_IMAGE";

    static final String EXPORT_OPTION = "--export";
    static final String PIN_OPTION = "--pin";
    static final String VERIFY_OPTION = "--verify";
    private static final String FILE_OPTION = "--file";
    private static final String REPORT_OPTION = "--report";

    /**
     * @param file  the images file to pin or verify, or {@code null} for that in effect, when verifying
     * @param names the images to pin or verify, or none for those that have a digest already
     * @param report the file to write the report of {@code --verify} to, as JSON or Markdown by its extension, or {@code null} for none
     */
    record PinArguments(Path file, List<String> names, Path report) {

        /**
         * @param args      {@code --pin|--verify [--file <file>] [<name>...]}
         * @param configDir the config dir, whose images file is pinned unless one is given
         * @throws IllegalArgumentException if they are not as above, or there is no file to pin
         */
        static PinArguments of(String[] args, String configDir) {
            return of(args, configDir, true);
        }

        /**
         * @param fileRequired whether there must be a file: to pin, the one given or that of the config dir; to verify, any images in effect will do
         */
        static PinArguments of(String[] args, String configDir, boolean fileRequired) {
            Path file = null;
            Path report = null;
            List<String> names = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                if (FILE_OPTION.equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(FILE_OPTION + " requires a file");
                    }
                    file = Path.of(args[++i]);
                } else if (REPORT_OPTION.equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(REPORT_OPTION + " requires a file, *.json or *.md");
                    }
                    report = Path.of(args[++i]);
                    DockerImageReport.checkWritable(report);
                } else if (args[i].startsWith("--")) {
                    throw new IllegalArgumentException("Unknown option " + args[i] + ", usage: docker-image " + PIN_OPTION + "|" + VERIFY_OPTION + " [" + FILE_OPTION + " <file>] [" + REPORT_OPTION + " <file>] [<name>...]");
                } else {
                    names.add(args[i]);
                }
            }
            if (file == null && fileRequired) {
                file = Path.of(configDir, DockerImageDefinitions.IMAGES_FILE);
                if (!java.nio.file.Files.isRegularFile(file)) {
                    throw new IllegalArgumentException(String.format(
                        "No %s to pin: export the bundled one to the config dir with docker-image %s, or give the file with %s",
                        file, EXPORT_OPTION, FILE_OPTION));
                }
            }
            return new PinArguments(file, names, report);
        }
    }

    /**
     * @return the line to print
     * @throws IllegalArgumentException if the args are not as above, or the image is not one of the images file
     */
    static List<String> lines(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: docker-image <name|" + EXPORT_OPTION + "|" + PIN_OPTION + "|" + VERIFY_OPTION + " ...>, one of " + DockerImages.all().keySet());
        }
        var image = DockerImages.all().get(args[0]);
        if (image == null) {
            throw new IllegalArgumentException("Unknown image '" + args[0] + "', expected one of " + DockerImages.all().keySet());
        }
        return List.of(VARIABLE + "=" + image.reference());
    }

    private static void pin(PinArguments arguments) {
        if (arguments.report() != null) {
            throw new IllegalArgumentException(REPORT_OPTION + " is for " + VERIFY_OPTION + ", not " + PIN_OPTION);
        }
        var changes = DockerImagePinner.pin(arguments.file(), arguments.names(), DockerImagePinner.docker(), DockerImages::version);
        changes.forEach(change -> System.out.printf("%-18s %s: %s%n", change.name(), change.reference(),
                                                    !change.isChanged() ? "digest unchanged" : change.before() == null
                                                        ? "pinned to " + change.after()
                                                        : change.before() + " -> " + change.after()));
        long changed = changes.stream().filter(DockerImagePinner.Change::isChanged).count();
        System.out.printf("Pinned %d images in %s, %d changed%n", changes.size(), arguments.file(), changed);
    }

    /**
     * @return whether each image checked has the digest its tag points to now
     */
    private static boolean verify(PinArguments arguments) {
        var images = arguments.file() == null ? DockerImages.all()
            : DockerImageDefinitions.load(arguments.file(), DockerImages::version);
        var checks = DockerImagePinner.verify(images, arguments.names(), DockerImagePinner.docker());
        checks.forEach(check -> System.out.printf("%-18s %s: %s%n", check.name(), check.reference(),
                                                  check.isOk() ? "OK"
                                                      : check.error() != null ? "ERROR " + check.error()
                                                      : check.pinned() == null ? "UNPINNED, the tag points to " + check.registry()
                                                      : "MISMATCH, the file has " + check.pinned() + " but the tag points to " + check.registry()));
        long failed = checks.stream().filter(check -> !check.isOk()).count();
        var unpinned = arguments.names().isEmpty() ? images.values().stream().filter(image -> image.digest() == null).toList()
            : List.<DockerImageDefinitions.Definition>of();
        System.out.printf("Verified %d images, %d failed%s%n", checks.size(), failed,
                          unpinned.isEmpty() ? "" : ", " + unpinned.size() + " unpinned and not checked:");
        unpinned.forEach(image -> System.out.printf("  %-18s %s%n", image.name(),
                                                    image.unpinned() != null ? image.unpinned() : "no digest in the file"));
        if (failed > 0) {
            System.out.printf("Update the digests with docker-image %s%n", PIN_OPTION);
        }
        if (arguments.report() != null) {
            new DockerImageReport(arguments.file() == null ? "the images in effect" : arguments.file().toString(), java.time.Instant.now(),
                                  checks.stream().map(DockerImageReport.Entry::of).toList(),
                                  unpinned.stream().map(DockerImageReport.Unpinned::of).toList()).write(arguments.report());
            System.out.printf("Report written to %s%n", arguments.report());
        }
        return failed == 0;
    }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && EXPORT_OPTION.equals(args[0])) {
                Path file = DockerImageDefinitions.exportBundled(Path.of(ConfigUtils.configDir()));
                System.out.printf("Exported the bundled Docker images to %s, which now replaces them: edit it to define the images differently%n", file);
                return;
            }
            if (args.length >= 1 && VERIFY_OPTION.equals(args[0])) {
                System.exit(verify(PinArguments.of(args, ConfigUtils.configDir(), false)) ? 0 : 1);
                return;
            }
            if (args.length >= 1 && PIN_OPTION.equals(args[0])) {
                pin(PinArguments.of(args, ConfigUtils.configDir()));
                return;
            }
            lines(args).forEach(System.out::println);
        } catch (IllegalArgumentException | IllegalStateException | ExceptionInInitializerError e) {
            System.err.println(e.getMessage() != null ? e.getMessage() : String.valueOf(e.getCause()));
            System.exit(1);
        }
    }
}
