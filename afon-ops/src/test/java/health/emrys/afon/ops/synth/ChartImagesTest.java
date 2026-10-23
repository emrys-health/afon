package health.emrys.afon.ops.synth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartImagesTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private static final String MANIFESTS = """
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: web
        spec:
          template:
            spec:
              initContainers:
                - name: init
                  image: "busybox:1.36"
              containers:
                - name: web
                  image: quay.io/acme/web:v1@%s
                  args:
                    - --acme-solver-image=quay.io/acme/solver:v1
                    - --verbose
        ---
        apiVersion: monitoring/v1
        kind: Prometheus
        spec:
          image: quay.io/prometheus/prometheus:v2.47.1
          other:
            image:
              repository: not-a-string-image
        ---
        apiVersion: v1
        kind: Pod
        metadata:
          name: test
          annotations:
            helm.sh/hook: test
        spec:
          containers:
            - name: t
              image: bats/bats:v1.4.1
        ---
        apiVersion: v1
        kind: ConfigMap
        data:
          image: "{{ not rendered }}"
        ---
        apiVersion: apps/v1
        kind: Deployment
        spec:
          template:
            spec:
              containers:
                - name: again
                  image: busybox:1.36
        """.formatted(DIGEST);

    @Test
    void findsTheImagesOfTheContainersTheArgumentsAndTheResourcesOnce() {
        var found = ChartImages.imagesIn("s", "r", "c.tgz", MANIFESTS);
        assertThat(found).extracting(ChartImages.Found::image).containsExactlyInAnyOrder(
            "busybox:1.36", "quay.io/acme/web:v1@" + DIGEST, "quay.io/acme/solver:v1", "quay.io/prometheus/prometheus:v2.47.1", "bats/bats:v1.4.1");
        assertThat(found).filteredOn(ChartImages.Found::test).extracting(ChartImages.Found::image).containsExactly("bats/bats:v1.4.1");
        assertThat(found).filteredOn(image -> image.kind().equals(ChartImages.ARGUMENT)).extracting(ChartImages.Found::image)
            .containsExactly("quay.io/acme/solver:v1");
    }

    @Test
    void aReferenceKnowsItsRepositoryTagAndDigest() {
        var pinned = new ChartImages.Found("s", "r", "c", "quay.io/acme/web:v1@" + DIGEST, ChartImages.IMAGE, false);
        assertThat(pinned.isPinned()).isTrue();
        assertThat(pinned.repository()).isEqualTo("quay.io/acme/web");
        assertThat(pinned.tag()).isEqualTo("v1");
        var untagged = new ChartImages.Found("s", "r", "c", "localhost:5000/acme/web", ChartImages.IMAGE, false);
        assertThat(untagged.isPinned()).isFalse();
        assertThat(untagged.tag()).isNull();
        assertThat(untagged.repository()).isEqualTo("localhost:5000/acme/web");
    }

    @Test
    void rendersEachReleaseOfThePlanWithItsValuesAndReportsOnesThatFail(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("cdk.tf.json"), """
            {"resource": {"helm_release": {
              "a": {"name": "alpha", "namespace": "ns", "chart": "assets/a/alpha-1.tgz", "values": ["x: 1"], "set": [{"name": "k", "value": "a,b"}]},
              "b": {"name": "beta", "chart": "assets/b/beta-1.tgz"}}}}
            """);
        List<String> calls = new ArrayList<>();
        ChartImages.Renderer renderer = (chart, release, namespace, values, sets) -> {
            calls.add(release + " " + chart.getFileName() + " " + namespace + " " + values + " " + sets);
            if (release.equals("beta")) {
                throw new IOException("no good");
            }
            return "kind: Pod\nspec:\n  containers:\n    - image: a/b:1\n";
        };
        var releases = ChartImages.inspect("stack", dir, renderer);
        assertThat(calls).containsExactly("alpha alpha-1.tgz ns [x: 1] [k=a\\,b]", "beta beta-1.tgz null [] []");
        assertThat(releases).hasSize(2);
        assertThat(releases.get(0).images()).extracting(ChartImages.Found::image).containsExactly("a/b:1");
        assertThat(releases.get(1).error()).isEqualTo("no good");
    }

    @Test
    void aStackWithNoPlanSaysToSynthesiseItFirst(@TempDir Path dir) {
        assertThatThrownBy(() -> ChartImages.inspect("aws-full", dir, (chart, release, namespace, values, sets) -> ""))
            .isInstanceOf(IOException.class).hasMessageContaining("validate-stack aws-full");
    }
}
