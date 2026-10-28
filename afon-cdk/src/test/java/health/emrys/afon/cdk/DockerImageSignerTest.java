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
        var signed = DockerImageSigner.sign(images(), List.of(), REGISTRY, KEY, commands::add, reference -> THEIRS);

        assertThat(signed).extracting(DockerImageSigner.Signed::name).containsExactly("spark", "keycloak");
        assertThat(signed.get(0).mirrored()).isFalse();
        assertThat(signed.get(0).signed()).isEqualTo("emryshealth/spark@" + OURS);
        assertThat(signed.get(1).mirrored()).isTrue();
        assertThat(signed.get(1).signed()).isEqualTo(REGISTRY + "/keycloak/keycloak@" + THEIRS);
        assertThat(commands).containsExactly(
            List.of("cosign", "sign", "--yes", "--use-signing-config=false", "--tlog-upload=false", "--new-bundle-format=false", "--key", KEY, "emryshealth/spark@" + OURS),
            List.of("docker", "buildx", "imagetools", "create", "--tag", REGISTRY + "/keycloak/keycloak:26", "quay.io/keycloak/keycloak@" + THEIRS),
            List.of("cosign", "sign", "--yes", "--use-signing-config=false", "--tlog-upload=false", "--new-bundle-format=false", "--key", KEY, REGISTRY + "/keycloak/keycloak@" + THEIRS));
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
}
