package health.emrys.afon.ops;

import health.emrys.afon.cdk.DockerImageDefinitions;
import health.emrys.afon.cdk.DockerImagePinner;
import health.emrys.afon.cdk.DockerImageSigner;
import health.emrys.afon.cdk.DockerImages;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.ops.config.OpsPropertyProvider;
import health.emrys.afon.ops.reports.ChartImageReport;
import health.emrys.afon.ops.reports.DockerImageReport;
import health.emrys.afon.ops.reports.SignReport;
import health.emrys.afon.ops.synth.ChartImages;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints the reference of a Docker image of {@code docker-images.yaml}, for a shell script that needs one, as
 * {@code AFON_DOCKER_IMAGE=<image>:<tag>[@<digest>]}.  Usage: {@code docker-image <name>}, eg {@code docker-image postgres-alpine}.
 * {@code docker-image --export} writes the bundled images file to the config dir instead, to edit it, and
 * {@code docker-image --pin [--file <file>] [<name>...]} looks up the digests of its images in their registries and writes them to it,
 * and {@code docker-image --verify [--file <file>] [--report <file>] [<name>...]} only checks that they are those the tags point to, failing if not,
 * and {@code docker-image --sign [--cloud <cloud>] [--report <file>] [<name>...]} signs them at their digests with the cosign key of the cloud, after mirroring those that are not ours
 * to its registry, and {@code docker-image --public-key [--cloud <cloud>]} prints the public key of that key, as the property that Kyverno verifies the images with.
 */
public class DockerImageMain {

    /**
     * The name of the line printed.
     */
    static final String VARIABLE = "AFON_DOCKER_IMAGE";

    static final String EXPORT_OPTION = "--export";
    static final String PIN_OPTION = "--pin";
    static final String CHARTS_OPTION = "--charts";
    static final String VERIFY_OPTION = "--verify";
    static final String SIGN_OPTION = "--sign";
    static final String PUBLIC_KEY_OPTION = "--public-key";
    private static final String CLOUD_OPTION = "--cloud";
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
     * @param cloud the cloud whose registry and key to use
     * @param report the file to write the record of {@code --sign} to, as JSON or Markdown by its extension, or {@code null} for none
     * @param names the images to sign, or none for those that have a digest
     */
    record SignArguments(AfonCloud cloud, List<String> names, Path report) {

        /**
         * @param args       {@code --sign [--cloud <cloud>] [--report <file>] [<name>...]}, or {@code --public-key [--cloud <cloud>]}
         * @param defaultCloud the cloud to use when none is given, eg that of {@code AFON_CLOUD}, blank or {@code ALL} for none
         * @throws IllegalArgumentException if they are not as above, or there is no single cloud
         */
        static SignArguments of(String[] args, String defaultCloud) {
            return of(args, defaultCloud, true);
        }

        /**
         * @param names whether names of images are accepted
         */
        static SignArguments of(String[] args, String defaultCloud, boolean names) {
            String cloud = defaultCloud;
            List<String> images = new ArrayList<>();
            Path report = null;
            for (int i = 1; i < args.length; i++) {
                if (CLOUD_OPTION.equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(CLOUD_OPTION + " requires a cloud, one of aws, azure or gcp");
                    }
                    cloud = args[++i];
                } else if (REPORT_OPTION.equals(args[i]) && names) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(REPORT_OPTION + " requires a file, *.json or *.md");
                    }
                    report = Path.of(args[++i]);
                    DockerImageReport.checkWritable(report);
                } else if (args[i].startsWith("--")) {
                    throw new IllegalArgumentException("Unknown option " + args[i] + ", usage: docker-image " + args[0] + " [" + CLOUD_OPTION + " <cloud>]" + (names ? " [" + REPORT_OPTION + " <file>] [<name>...]" : ""));
                } else if (!names) {
                    throw new IllegalArgumentException(args[0] + " takes no image names, only " + CLOUD_OPTION + " <cloud>");
                } else {
                    images.add(args[i]);
                }
            }
            if (cloud == null || cloud.isBlank() || ConfigUtils.CLOUD_ALL.equalsIgnoreCase(cloud)) {
                throw new IllegalArgumentException("Signing needs one cloud, whose registry and key to use: give " + CLOUD_OPTION + " <aws|azure|gcp>, or set " + ConfigUtils.AFON_CLOUD_ENV);
            }
            try {
                return new SignArguments(AfonCloud.valueOf(cloud.toUpperCase(java.util.Locale.ROOT)), images, report);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown cloud '" + cloud + "', expected one of aws, azure or gcp");
            }
        }
    }

    private static void publicKey(SignArguments arguments) {
        var properties = new OpsPropertyProvider();
        String key = properties.getCloudValue(arguments.cloud(), AfonCloudProperty.COSIGN_KEY, "");
        if (key.isBlank()) {
            throw new IllegalArgumentException(String.format("Set %s to export its public key",
                AfonCloudProperty.COSIGN_KEY.forCloud(arguments.cloud()).orElseThrow()));
        }
        try {
            System.out.printf("%s=%s%n", AfonCloudProperty.COSIGN_PUBLIC_KEY.forCloud(arguments.cloud()).orElseThrow(),
                              DockerImageSigner.publicKey(key, DockerImageSigner.outputs()));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static void sign(SignArguments arguments) {
        var properties = new OpsPropertyProvider();
        String registry = properties.getCloudValue(arguments.cloud(), AfonCloudProperty.IMAGE_REGISTRY, "");
        String key = properties.getCloudValue(arguments.cloud(), AfonCloudProperty.COSIGN_KEY, "");
        if (registry.isBlank() || key.isBlank()) {
            throw new IllegalArgumentException(String.format("Set %s and %s to sign the images for %s",
                AfonCloudProperty.IMAGE_REGISTRY.forCloud(arguments.cloud()).orElseThrow(),
                AfonCloudProperty.COSIGN_KEY.forCloud(arguments.cloud()).orElseThrow(), arguments.cloud()));
        }
        List<DockerImageSigner.Signed> signed = new ArrayList<>();
        String error = null;
        try {
            DockerImageSigner.sign(DockerImages.all(), arguments.names(), registry, key, DockerImageSigner.processes(),
                                   DockerImagePinner.docker(), signed::add);
        } catch (IOException | IllegalStateException e) {
            error = e.getMessage();
        }
        signed.forEach(image -> System.out.printf("%-18s %s%s%n", image.name(), image.signed(),
                                                  image.mirrored() ? "  [mirrored from " + image.source() + "]" : ""));
        System.out.printf("Signed %d images with %s%n", signed.size(), key);
        if (arguments.report() != null) {
            // written even when signing failed, to record what was signed before
            new SignReport(arguments.cloud().toString(), registry, key, java.time.Instant.now(), signed, error).write(arguments.report());
            System.out.printf("Record written to %s%n", arguments.report());
        }
        if (error != null) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * @return the line to print
     * @throws IllegalArgumentException if the args are not as above, or the image is not one of the images file
     */
    static List<String> lines(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: docker-image <name|" + EXPORT_OPTION + "|" + PIN_OPTION + "|" + VERIFY_OPTION + "|" + SIGN_OPTION + "|" + PUBLIC_KEY_OPTION + "|" + CHARTS_OPTION + " ...>, one of " + DockerImages.all().keySet());
        }
        var image = DockerImages.all().get(args[0]);
        if (image == null) {
            throw new IllegalArgumentException("Unknown image '" + args[0] + "', expected one of " + DockerImages.all().keySet());
        }
        return List.of(VARIABLE + "=" + image.reference());
    }

    /**
     * Reports the images that the Helm charts of the stacks pull, from the plans that were synthesised for them.
     *
     * @return whether every release could be rendered
     */
    private static boolean charts(PinArguments arguments) {
        try {
            Process helm = new ProcessBuilder("helm", "version", "--short").redirectErrorStream(true).start();
            helm.getInputStream().readAllBytes();
            if (helm.waitFor() != 0) {
                throw new IllegalStateException("helm does not run: it renders the charts");
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("helm is required to render the charts, and is not installed");
        }
        Path stacksDir = Path.of(ConfigUtils.stacksDir());
        List<String> stacks = arguments.names();
        if (stacks.isEmpty()) {
            try (var dirs = java.nio.file.Files.list(stacksDir)) {
                stacks = dirs.filter(dir -> java.nio.file.Files.isRegularFile(dir.resolve("cdk.tf.json")))
                    .map(dir -> dir.getFileName().toString()).sorted().toList();
            } catch (IOException e) {
                stacks = List.of();
            }
        }
        if (stacks.isEmpty()) {
            throw new IllegalArgumentException("No stack is synthesised in " + stacksDir + ": synthesise the stacks first, eg with validate-stack <stack-id>");
        }
        List<ChartImages.Release> releases = new ArrayList<>();
        for (String stack : stacks) {
            try {
                releases.addAll(ChartImages.inspect(stack, stacksDir.resolve(stack), ChartImages.helm()));
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        var report = new ChartImageReport(stacks, releases, ChartImages.listedBy(DockerImages.all()), java.time.Instant.now());
        String current = null;
        for (ChartImages.Release release : releases) {
            if (!release.stack().equals(current)) {
                current = release.stack();
                System.out.printf("== %s%n", current);
            }
            System.out.printf("%s (%s)%n", release.release(), release.chart());
            if (release.error() != null) {
                System.out.printf("    ERROR %s%n", release.error());
            }
            release.images().stream().filter(found -> !found.test()).forEach(found -> System.out.printf("    %s  %s%s%n", found.image(),
                found.isPinned() ? "pinned" : found.tag() == null ? "NO TAG" : "latest".equals(found.tag()) ? "LATEST" : "tag only",
                report.entries(false).stream().filter(entry -> entry.image().equals(found.image()) && entry.listedAs() != null).findFirst()
                    .map(entry -> "  [in docker-images.yaml: " + entry.listedAs() + "]").orElse("")));
        }
        var entries = report.entries(false);
        long tests = report.entries(true).size() - entries.size();
        System.out.printf("%d images in %d releases of %d stacks, %d pinned by digest, %d only in helm test pods, %d releases not rendered%n",
                          entries.size(), releases.size(), stacks.size(), entries.stream().filter(ChartImageReport.Entry::pinned).count(), tests,
                          report.failed());
        if (arguments.report() != null) {
            report.write(arguments.report());
            System.out.printf("Report written to %s%n", arguments.report());
        }
        return report.failed() == 0;
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
            if (args.length >= 1 && SIGN_OPTION.equals(args[0])) {
                sign(SignArguments.of(args, ConfigUtils.afonCloud()));
                return;
            }
            if (args.length >= 1 && PUBLIC_KEY_OPTION.equals(args[0])) {
                publicKey(SignArguments.of(args, ConfigUtils.afonCloud(), false));
                return;
            }
            if (args.length >= 1 && CHARTS_OPTION.equals(args[0])) {
                System.exit(charts(PinArguments.of(args, ConfigUtils.configDir(), false)) ? 0 : 1);
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
