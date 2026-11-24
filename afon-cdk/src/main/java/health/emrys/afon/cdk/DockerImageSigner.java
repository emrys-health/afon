package health.emrys.afon.cdk;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Signs the images of a {@code docker-images.yaml} with cosign, at the digest they are pinned to.  The images that are not first-party are first
 * mirrored to the registry of the stack at that digest, so that the signature, and what the cluster pulls, are in our registry.  The
 * signatures are not uploaded to a transparency log, so that the cluster, which verifies them with the public key alone, needs no access to one.
 * The flags are those of cosign 3, which refuses {@code --tlog-upload=false} unless the signing config it takes from TUF is switched off too,
 * and signs in the legacy layout, a {@code sha256-<digest>.sig} tag, rather than its new bundle format, which the admission controller may not read.
 */
public final class DockerImageSigner {

    private static final Logger logger = getLogger(DockerImageSigner.class);

    private static final int COMMAND_TIMEOUT_SECONDS = 300;
    private static final File NULL_DEVICE = new File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null");

    /**
     * Runs the commands that mirror and sign.
     */
    @FunctionalInterface
    public interface CommandRunner {
        /**
         * @throws IOException if the command cannot be run or fails
         */
        void run(List<String> command) throws IOException;
    }

    /**
     * What signing did to an image.
     *
     * @param name     the name of the image
     * @param source   the image at its pinned digest, as the images file has it
     * @param signed   the image at its digest that was signed: the source if it is first-party, else its copy in the registry
     * @param mirrored whether it was copied to the registry
     */
    public record Signed(String name, String source, String signed, boolean mirrored) {
    }

    /**
     * What checking the signature of an image found.
     *
     * @param name  the name of the image
     * @param image the image at its digest that was checked: the image if it is first-party, else its copy in the registry
     * @param error why it has no signature that verifies with the key, or {@code null} if it has one
     */
    public record Verified(String name, String image, String error) {
        public boolean isOk() {
            return error == null;
        }
    }

    /**
     * Runs a command and returns what it prints.
     */
    @FunctionalInterface
    public interface CommandOutput {
        /**
         * @throws IOException if the command cannot be run or fails
         */
        String output(List<String> command) throws IOException;
    }

    /**
     * Whether an image, at its digest, has a signature that verifies with the key that signs.
     */
    @FunctionalInterface
    public interface SignatureCheck {
        boolean verifies(String reference);
    }

    private DockerImageSigner() {
    }

    /**
     * Mirrors the images that are not first-party to the registry, if they are not there already, and signs every image at its digest.  An
     * image is signed again, which cosign allows, adding a signature, unless it has a signature that verifies, see
     * {@link #sign(Map, List, String, String, CommandRunner, DockerImagePinner.DigestResolver, Consumer, SignatureCheck)}, which this does not use.
     *
     * @param images   the images, as defined in the file
     * @param names    the images to sign, or none for those that have a digest
     * @param registry the registry, with the path in it, to mirror to, eg {@code 123.dkr.ecr.eu-west-2.amazonaws.com/emrys}
     * @param key      the cosign key reference, as {@code cosign sign --key} takes it
     * @param commands runs the commands
     * @param resolver looks up the digest of a copy, to check it has that of the source
     * @return what was done for each image, in the order of the file
     * @throws IllegalStateException    if a name is not one of the images, or an image to sign has no digest, or a copy has another digest
     * @throws IllegalArgumentException if the registry or the key is blank
     */
    public static List<Signed> sign(Map<String, DockerImageDefinitions.Definition> images, List<String> names, String registry, String key,
                                    CommandRunner commands, DockerImagePinner.DigestResolver resolver) throws IOException {
        return sign(images, names, registry, key, commands, resolver, image -> {
        });
    }

    /**
     * @return a check of the signatures with the public key of {@code key}, derived from it, not read from the config, so that an image that
     * was signed with a key that this has replaced is signed again.  It needs no private key, only the registry.  A check that fails for any reason,
     * eg the registry cannot be reached, says that the image is not signed, so that the image is signed.
     * @throws IOException if the public key cannot be had from the key
     */
    public static SignatureCheck verifiesWith(String key, CommandOutput commands) throws IOException {
        String pem = PublicKeys.fromProperty(publicKey(key, commands));
        java.nio.file.Path file = java.nio.file.Files.createTempFile("afon-cosign-", ".pub");
        file.toFile().deleteOnExit();
        java.nio.file.Files.writeString(file, pem + "\n", StandardCharsets.UTF_8);
        return reference -> {
            try {
                commands.output(List.of("cosign", "verify", "--key", file.toString(), "--insecure-ignore-tlog=true", reference));
                return true;
            } catch (IOException e) {
                return false;
            }
        };
    }

    /**
     * As {@link #sign(Map, List, String, String, CommandRunner, DockerImagePinner.DigestResolver)}, telling the listener of each image as
     * soon as it is signed, so that what was done is known if a later image fails.
     */
    public static List<Signed> sign(Map<String, DockerImageDefinitions.Definition> images, List<String> names, String registry, String key,
                                    CommandRunner commands, DockerImagePinner.DigestResolver resolver, Consumer<Signed> listener)
        throws IOException {
        return sign(images, names, registry, key, commands, resolver, listener, reference -> false);
    }

    /**
     * As {@link #sign(Map, List, String, String, CommandRunner, DockerImagePinner.DigestResolver)}, telling the listener of each image as
     * soon as it is signed, so that what was done is known if a later image fails, and not signing an image again that already has a signature
     * that verifies, see {@link #verifiesWith}, as it would only add another.  Such an image is still reported as signed, and told to the listener.
     */
    public static List<Signed> sign(Map<String, DockerImageDefinitions.Definition> images, List<String> names, String registry, String key,
                                    CommandRunner commands, DockerImagePinner.DigestResolver resolver, Consumer<Signed> listener,
                                    SignatureCheck check) throws IOException {
        if (registry == null || registry.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("Both a registry and a cosign key are required to sign");
        }
        for (String name : names) {
            if (!images.containsKey(name)) {
                throw new IllegalStateException(String.format("Unknown image '%s', expected one of %s", name, images.keySet()));
            }
        }
        List<DockerImageDefinitions.Definition> chosen = images.values().stream()
            .filter(image -> names.isEmpty() ? image.digest() != null : names.contains(image.name())).toList();
        // fail before anything is copied or signed
        for (DockerImageDefinitions.Definition image : chosen) {
            if (image.digest() == null) {
                throw new IllegalStateException(String.format(
                    "%s has no digest to sign: %s", image.name(), image.unpinned() != null ? image.unpinned() : "pin it with --pin"));
            }
        }
        logger.info("Signing {} images with {}, to the registry {}", chosen.size(), key, registry);
        List<Signed> signed = new ArrayList<>();
        for (DockerImageDefinitions.Definition image : chosen) {
            String source = image.image() + "@" + image.digest();
            boolean mirror = image.isMirrored(registry);
            String target = image.repository(registry);
            if (mirror) {
                // Nothing is copied if the tag already has the digest that is signed, ie from an earlier run: copying is the slow part.  A tag that
                // cannot be looked up, eg because it is not there yet, is copied.
                String existing = digestOrNull(resolver, target + ":" + image.tag());
                if (image.digest().equals(existing)) {
                    logger.info("{} is already in the registry as {}:{} at {}, not copying it", image.name(), target, image.tag(), image.digest());
                } else {
                    logger.info("Copying {} to {}:{}{}", source, target, image.tag(),
                                existing == null ? "" : ", which has the digest " + existing + " now");
                    // oras copies by digest, so the copy has the digest that is signed, whatever its media type: docker buildx imagetools create
                    // wraps an image that is a single manifest, rather than an index, in a manifest list, which has another digest
                    commands.run(List.of("oras", "cp", qualified(source), target + ":" + image.tag()));
                }
                String copy = resolver.digest(target + ":" + image.tag());
                logger.debug("The copy of {} has the digest {}", image.name(), copy);
                if (!copy.equals(image.digest())) {
                    throw new IllegalStateException(String.format(
                        "The copy of %s in %s has the digest %s, not %s", image.name(), registry, copy, image.digest()));
                }
            }
            if (check.verifies(target + "@" + image.digest())) {
                logger.info("{} is already signed with the key, at {}, not signing it again", image.name(), target + "@" + image.digest());
            } else {
                commands.run(List.of("cosign", "sign", "--yes", "--use-signing-config=false", "--tlog-upload=false", "--new-bundle-format=false", "--key", key, target + "@" + image.digest()));
            }
            Signed done = new Signed(image.name(), source, target + "@" + image.digest(), mirror);
            signed.add(done);
            listener.accept(done);
        }
        return signed;
    }

    /**
     * Checks that every image has a signature that verifies with the public key, where {@link #sign} puts it: the image if it is first-party, else its
     * copy in the registry.  Nothing is signed, copied or pushed, and the key is not needed, only the public key.  An image whose check fails
     * is reported with the reason, and the others are still checked.
     *
     * @param images    the images, as defined in the file
     * @param names     the images to check, or none for those that have a digest; a named image with no digest fails its check
     * @param registry  the registry, with the path in it, that the images that are not first-party were mirrored to
     * @param publicKey the public key, as the {@code <CLOUD>_COSIGN_PUBLIC_KEY} property holds it, see {@link PublicKeys}
     * @param commands  runs {@code cosign verify}
     * @return the result for each image, in the order of the file
     * @throws IllegalStateException    if a name is not one of the images
     * @throws IllegalArgumentException if the registry or the public key is blank
     */
    public static List<Verified> verify(Map<String, DockerImageDefinitions.Definition> images, List<String> names, String registry,
                                        String publicKey, CommandRunner commands) throws IOException {
        if (registry == null || registry.isBlank() || publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("Both a registry and a public key are required to verify the signatures");
        }
        for (String name : names) {
            if (!images.containsKey(name)) {
                throw new IllegalStateException(String.format("Unknown image '%s', expected one of %s", name, images.keySet()));
            }
        }
        List<DockerImageDefinitions.Definition> chosen = images.values().stream()
            .filter(image -> names.isEmpty() ? image.digest() != null : names.contains(image.name())).toList();
        logger.info("Verifying the signatures of {} images in the registry {}", chosen.size(), registry);
        List<Verified> verified = new ArrayList<>();
        java.nio.file.Path keyFile = java.nio.file.Files.createTempFile("afon-cosign-", ".pub");
        try {
            java.nio.file.Files.writeString(keyFile, PublicKeys.fromProperty(publicKey) + "\n", StandardCharsets.UTF_8);
            for (DockerImageDefinitions.Definition image : chosen) {
                if (image.digest() == null) {
                    verified.add(new Verified(image.name(), image.image(), "no digest to verify: "
                        + (image.unpinned() != null ? image.unpinned() : "pin it with --pin")));
                    continue;
                }
                String target = image.repository(registry) + "@" + image.digest();
                try {
                    commands.run(List.of("cosign", "verify", "--key", keyFile.toString(), "--insecure-ignore-tlog=true", target));
                    verified.add(new Verified(image.name(), target, null));
                } catch (IOException e) {
                    logger.debug("Verifying {} failed: {}", target, e.getMessage());
                    verified.add(new Verified(image.name(), target, reason(e)));
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(keyFile);
        }
        return verified;
    }

    /**
     * @return why cosign failed to verify, in a few words if it says that the image has no signature, else what it printed
     */
    private static String reason(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("no signatures found")) {
            return "not signed";
        }
        if (message.contains("no matching signatures")) {
            return "signed, but not with this key";
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    /**
     * @param key      the cosign key reference, as {@code cosign public-key --key} takes it, eg {@code awskms:///alias/afon-image-signing}
     * @param commands runs the command
     * @return the public key of the key, as the {@code <CLOUD>_COSIGN_PUBLIC_KEY} property holds it, see {@link PublicKeys}
     * @throws IllegalArgumentException if the key is blank, or what cosign prints is not a public key
     */
    public static String publicKey(String key, CommandOutput commands) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A cosign key is required to export its public key");
        }
        return PublicKeys.toProperty(commands.output(List.of("cosign", "public-key", "--key", key)));
    }

    private static String digestOrNull(DockerImagePinner.DigestResolver resolver, String reference) {
        try {
            return resolver.digest(reference);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @return the reference with its registry, as oras needs it, whereas docker takes {@code curlimages/curl} for Docker Hub's and
     * {@code postgres} for its {@code library/postgres}: a first component is a registry if it has a dot or a colon, or is localhost
     */
    static String qualified(String reference) {
        int slash = reference.indexOf('/');
        String first = slash < 0 ? "" : reference.substring(0, slash);
        if (slash >= 0 && (first.contains(".") || first.contains(":") || first.equals("localhost"))) {
            return reference;
        }
        return "docker.io/" + (slash < 0 ? "library/" : "") + reference;
    }

    /**
     * @return the repository of the copy of an image in the registry, see {@link DockerImageDefinitions#mirrorRepository}
     */
    static String mirrorRepository(String registry, String image) {
        return DockerImageDefinitions.mirrorRepository(registry, image);
    }

    /**
     * @return a runner that runs each command as a process, failing with its output if it exits with other than 0
     */
    public static CommandRunner processes() {
        CommandOutput outputs = outputs();
        return command -> outputs.output(command);
    }

    /**
     * @return a runner that runs each command as a process and returns its output, failing with it if it exits with other than 0
     */
    public static CommandOutput outputs() {
        return command -> {
            try {
                // with no input, as a command that asks for something, eg cosign for the password of a key, would wait for it for ever, its
                // output being read here and not shown
                Process process = new ProcessBuilder(command).redirectErrorStream(true)
                                                             .redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE)).start();
                String output;
                try (var in = process.getInputStream()) {
                    output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Timed out running " + String.join(" ", command));
                }
                if (process.exitValue() != 0) {
                    throw new IOException(String.format("%s failed: %s%s", String.join(" ", command), output,
                                                        output.toLowerCase(Locale.ROOT).contains("password")
                                                            ? "%nThe key asks for a password: set COSIGN_PASSWORD, empty if it has none".formatted() : ""));
                }
                return output;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted running " + String.join(" ", command), e);
            }
        };
    }
}
