package health.emrys.afon.cdk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerImageSignerTest {

    private static final String REGISTRY = "123.dkr.ecr.eu-west-2.amazonaws.com/emrys";
    private static final String KEY = "awskms:///alias/sign";
    private static final String OURS = "sha256:" + "a".repeat(64);
    private static final String THEIRS = "sha256:" + "b".repeat(64);

    private final List<List<String>> commands = new ArrayList<>();

    private Map<String, DockerImageDefinitions.Definition> images() {
        Map<String, DockerImageDefinitions.Definition> images = new LinkedHashMap<>();
        images.put("spark", new DockerImageDefinitions.Definition("spark", "emryshealth/spark", "4.0.1", OURS, "first-party", null));
        images.put("keycloak", new DockerImageDefinitions.Definition("keycloak", "quay.io/keycloak/keycloak", "26", THEIRS, "upstream", null));
        images.put("trino", new DockerImageDefinitions.Definition("trino", "trinodb/trino", "481", null, "upstream", "chart image"));
        return images;
    }

    @Test
    void signsOurImagesInPlaceAndMirrorsTheOthersFirst() throws IOException {
        // the copy is not in the registry until oras has made it
        var signed = DockerImageSigner.sign(images(), List.of(), REGISTRY, KEY, commands::add, reference -> {
            if (commands.stream().noneMatch(command -> command.contains("oras"))) {
                throw new IOException("not found");
            }
            return THEIRS;
        });

        assertThat(signed).extracting(DockerImageSigner.Signed::name).containsExactly("spark", "keycloak");
        assertThat(signed.get(0).mirrored()).isFalse();
        assertThat(signed.get(0).signed()).isEqualTo("emryshealth/spark@" + OURS);
        assertThat(signed.get(1).mirrored()).isTrue();
        assertThat(signed.get(1).signed()).isEqualTo(REGISTRY + "/keycloak/keycloak@" + THEIRS);
        assertThat(commands).containsExactly(
            List.of("cosign", "sign", "--yes", "--use-signing-config=false", "--tlog-upload=false", "--new-bundle-format=false", "--key", KEY, "emryshealth/spark@" + OURS),
            List.of("oras", "cp", "quay.io/keycloak/keycloak@" + THEIRS, REGISTRY + "/keycloak/keycloak:26"),
            List.of("cosign", "sign", "--yes", "--use-signing-config=false", "--tlog-upload=false", "--new-bundle-format=false", "--key", KEY, REGISTRY + "/keycloak/keycloak@" + THEIRS));
    }

    @Test
    void doesNotCopyACopyThatIsThereWithTheDigest() throws IOException {
        var signed = DockerImageSigner.sign(images(), List.of("keycloak"), REGISTRY, KEY, commands::add, reference -> THEIRS);

        assertThat(signed).singleElement().satisfies(image -> assertThat(image.mirrored()).isTrue());
        assertThat(commands).noneMatch(command -> command.contains("oras"));
        assertThat(commands).singleElement().satisfies(command -> assertThat(command).contains("cosign", REGISTRY + "/keycloak/keycloak@" + THEIRS));
    }

    @Test
    void doesNotSignAgainAnImageThatHasASignatureThatVerifies() throws IOException {
        var checked = new java.util.ArrayList<String>();
        var signed = DockerImageSigner.sign(images(), List.of(), REGISTRY, KEY, commands::add, reference -> THEIRS, image -> {
        }, reference -> {
            checked.add(reference);
            return reference.startsWith(REGISTRY);
        });

        assertThat(signed).extracting(DockerImageSigner.Signed::name).containsExactly("spark", "keycloak");
        assertThat(checked).containsExactly("emryshealth/spark@" + OURS, REGISTRY + "/keycloak/keycloak@" + THEIRS);
        // spark does not verify, so it is signed; keycloak does, so it is not, and is not copied, as its tag has the digest
        assertThat(commands).singleElement().satisfies(command -> assertThat(command).contains("cosign", "sign", "emryshealth/spark@" + OURS));
    }

    @Test
    void verifiesWithThePublicKeyDerivedFromTheSigningKey() throws IOException {
        var run = new java.util.ArrayList<List<String>>();
        var check = DockerImageSigner.verifiesWith(KEY, command -> {
            run.add(command);
            if (command.contains("public-key")) {
                return "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEabc\n-----END PUBLIC KEY-----";
            }
            if (command.stream().anyMatch(part -> part.contains("/bad@"))) {
                throw new IOException("no signatures found");
            }
            return "ok";
        });

        assertThat(check.verifies("r/good@sha256:a")).isTrue();
        assertThat(check.verifies("r/bad@sha256:a")).isFalse();
        assertThat(run.get(0)).containsExactly("cosign", "public-key", "--key", KEY);
        assertThat(run.get(1)).contains("cosign", "verify", "--insecure-ignore-tlog=true", "r/good@sha256:a").doesNotContain(KEY);
    }

    @Test
    void qualifiesTheSourceWithItsRegistryAsOrasNeedsIt() {
        assertThat(DockerImageSigner.qualified("curlimages/curl@sha256:a")).isEqualTo("docker.io/curlimages/curl@sha256:a");
        assertThat(DockerImageSigner.qualified("postgres@sha256:a")).isEqualTo("docker.io/library/postgres@sha256:a");
        assertThat(DockerImageSigner.qualified("quay.io/keycloak/keycloak@sha256:a")).isEqualTo("quay.io/keycloak/keycloak@sha256:a");
        assertThat(DockerImageSigner.qualified("localhost:5000/x@sha256:a")).isEqualTo("localhost:5000/x@sha256:a");
        assertThat(DockerImageSigner.qualified("localhost/x@sha256:a")).isEqualTo("localhost/x@sha256:a");
    }

    @Test
    void refusesACopyWithAnotherDigest() {
        assertThatThrownBy(() -> DockerImageSigner.sign(images(), List.of("keycloak"), REGISTRY, KEY, commands::add, reference -> OURS))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("has the digest " + OURS);
        assertThat(commands).noneMatch(command -> command.contains("cosign"));
    }

    @Test
    void refusesAnUnpinnedOrUnknownImageBeforeDoingAnything() {
        assertThatThrownBy(() -> DockerImageSigner.sign(images(), List.of("spark", "trino"), REGISTRY, KEY, commands::add, reference -> OURS))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("trino has no digest to sign: chart image");
        assertThatThrownBy(() -> DockerImageSigner.sign(images(), List.of("nope"), REGISTRY, KEY, commands::add, reference -> OURS))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown image 'nope'");
        assertThat(commands).isEmpty();
    }

    @Test
    void needsARegistryAndAKey() {
        assertThatThrownBy(() -> DockerImageSigner.sign(images(), List.of(), " ", KEY, commands::add, reference -> OURS))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerImageSigner.sign(images(), List.of(), REGISTRY, "", commands::add, reference -> OURS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mirrorsUnderThePathOfTheImageWithoutItsRegistry() {
        assertThat(DockerImageSigner.mirrorRepository("r.io/emrys/", "quay.io/keycloak/keycloak")).isEqualTo("r.io/emrys/keycloak/keycloak");
        assertThat(DockerImageSigner.mirrorRepository("r.io/emrys", "trinodb/trino")).isEqualTo("r.io/emrys/trinodb/trino");
        assertThat(DockerImageSigner.mirrorRepository("r.io/emrys", "postgres")).isEqualTo("r.io/emrys/postgres");
        assertThat(DockerImageSigner.mirrorRepository("r.io/emrys", "localhost:5000/x")).isEqualTo("r.io/emrys/x");
    }

    @Test
    void exportsThePublicKeyAsASingleWordIgnoringWhatElseCosignPrints() throws IOException {
        String pem = "-----BEGIN PUBLIC KEY-----\nMFkw\nabc=\n-----END PUBLIC KEY-----";
        var commands = new ArrayList<List<String>>();

        String key = DockerImageSigner.publicKey(KEY, command -> {
            commands.add(command);
            return "WARNING: something\n" + pem + "\n";
        });

        assertThat(commands).containsExactly(List.of("cosign", "public-key", "--key", KEY));
        // no header, no space: the shell reads it too, when it sources the config
        assertThat(key).isEqualTo("MFkwabc=");
        assertThat(PublicKeys.fromProperty(key)).isEqualTo("-----BEGIN PUBLIC KEY-----\nMFkwabc=\n-----END PUBLIC KEY-----");
        // as it used to be written
        assertThat(PublicKeys.fromProperty("-----BEGIN PUBLIC KEY-----\\nMFkw\\nabc=\\n-----END PUBLIC KEY-----")).isEqualTo(pem);
        assertThatThrownBy(() -> DockerImageSigner.publicKey(KEY, command -> "no key here")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerImageSigner.publicKey(" ", command -> pem)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tellsTheListenerOfEachImageAsSoonAsItIsSigned() {
        var told = new ArrayList<String>();
        DockerImageSigner.CommandRunner failsOnTheSecond = command -> {
            if (command.contains(REGISTRY + "/keycloak/keycloak@" + THEIRS)) {
                throw new IOException("denied");
            }
        };

        assertThatThrownBy(() -> DockerImageSigner.sign(images(), List.of(), REGISTRY, KEY, failsOnTheSecond, reference -> THEIRS,
                                                        image -> told.add(image.name())))
            .isInstanceOf(IOException.class).hasMessage("denied");

        assertThat(told).containsExactly("spark");
    }

    @Test
    void theProcessesGetNoInputSoThatACommandAskingForSomethingFailsInsteadOfWaiting() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));

        // cat copies its input, which is empty, and would wait for ever on an open pipe
        assertThat(DockerImageSigner.outputs().output(List.of("cat"))).isEmpty();
    }

    @Test
    void saysToSetThePasswordWhenTheKeyAsksForOne() {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));

        assertThatThrownBy(() -> DockerImageSigner.outputs().output(List.of("sh", "-c", "echo 'Enter password for private key:'; exit 1")))
            .isInstanceOf(IOException.class).hasMessageContaining("Enter password").hasMessageContaining("set COSIGN_PASSWORD, empty if it has none");
    }

    private static final String PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEexample";

    @Test
    void verifiesTheImagesWhereTheyAreSignedWithThePublicKey() throws IOException {
        var verified = DockerImageSigner.verify(images(), List.of(), REGISTRY, PUBLIC_KEY, commands::add);

        assertThat(verified).extracting(DockerImageSigner.Verified::name).containsExactly("spark", "keycloak");
        assertThat(verified).allMatch(DockerImageSigner.Verified::isOk);
        assertThat(verified.get(0).image()).isEqualTo("emryshealth/spark@" + OURS);
        assertThat(verified.get(1).image()).isEqualTo(REGISTRY + "/keycloak/keycloak@" + THEIRS);
        assertThat(commands).hasSize(2).allSatisfy(command -> assertThat(command)
            .startsWith("cosign", "verify", "--key").contains("--insecure-ignore-tlog=true"));
        assertThat(commands.get(0).get(commands.get(0).size() - 1)).isEqualTo("emryshealth/spark@" + OURS);
        assertThat(commands.get(1).get(commands.get(1).size() - 1)).isEqualTo(REGISTRY + "/keycloak/keycloak@" + THEIRS);
    }

    @Test
    void verifyReportsEachImageThatFailsAndStillChecksTheOthers() throws IOException {
        var verified = DockerImageSigner.verify(images(), List.of("spark", "keycloak", "trino"), REGISTRY, PUBLIC_KEY, command -> {
            if (command.get(command.size() - 1).startsWith("emryshealth/spark")) {
                throw new IOException("cosign verify failed: Error: no signatures found");
            }
        });

        assertThat(verified).extracting(DockerImageSigner.Verified::error)
            .containsExactly("not signed", null, "no digest to verify: chart image");
    }

    @Test
    void verifyNeedsARegistryAndAPublicKeyAndKnownNames() {
        assertThatThrownBy(() -> DockerImageSigner.verify(images(), List.of(), "", PUBLIC_KEY, commands::add))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerImageSigner.verify(images(), List.of(), REGISTRY, " ", commands::add))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerImageSigner.verify(images(), List.of("nope"), REGISTRY, PUBLIC_KEY, commands::add))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unknown image 'nope'");
        assertThat(commands).isEmpty();
    }
}
