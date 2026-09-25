package health.emrys.afon.cdk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the destroy-time CRD cleanup script for real, under {@code sh}, against a stub
 * {@code kubectl} that models a cluster: a list of CRDs, custom resources whose finalizers keep
 * their CRD in Terminating, and CRDs that never go away.
 */
class CrdCleanupTest {

    private static final String PROM = "customresourcedefinition.apiextensions.k8s.io/prometheuses.monitoring.coreos.com";
    private static final String SERVICE_MONITORS = "customresourcedefinition.apiextensions.k8s.io/servicemonitors.monitoring.coreos.com";
    private static final String GRAFANA_AGENTS = "customresourcedefinition.apiextensions.k8s.io/grafanaagents.monitoring.grafana.com";
    private static final String CILIUM_POLICIES = "customresourcedefinition.apiextensions.k8s.io/ciliumnetworkpolicies.cilium.io";
    private static final String CILIUM_IDENTITIES = "customresourcedefinition.apiextensions.k8s.io/ciliumidentities.cilium.io";
    private static final String SPARK = "customresourcedefinition.apiextensions.k8s.io/sparkapplications.spark.apache.org";
    private static final String EKS_MANAGED = "customresourcedefinition.apiextensions.k8s.io/policyendpoints.networking.k8s.aws";
    private static final String LBC = "customresourcedefinition.apiextensions.k8s.io/targetgroupbindings.elbv2.k8s.aws";
    private static final String LOOKALIKE = "customresourcedefinition.apiextensions.k8s.io/things.notcilium.io";

    /**
     * kubectl stand-in. State lives in files under $STUB: {@code crds} (one CRD reference per line),
     * {@code deleting} (CRDs asked to be deleted), {@code undeletable} (never go away),
     * {@code needs_crd_patch} (only go once their own finalizers are removed), {@code crd_patched},
     * and {@code cr_<plural.group>} (one "namespace/name" per custom resource still holding
     * finalizers; the CRD cannot go while that file is non-empty).
     */
    private static final String KUBECTL = """
        #!/bin/sh
        args=""
        for a in "$@"; do
          case "$a" in --request-timeout=*) ;; *) args="$args $a" ;; esac
        done
        set -- $args
        echo "$*" >> "$STUB/calls"
        cmd=$1
        shift
        case "$cmd" in
          get)
            if [ "$1" = crd ]; then
              if [ -f "$STUB/unreachable" ]; then echo "connection refused" >&2; exit 1; fi
              : > "$STUB/crds.new"
              while read -r ref; do
                res=${ref#*/}
                if grep -qx "$ref" "$STUB/deleting" && ! grep -qx "$ref" "$STUB/undeletable" \\
                   && [ ! -s "$STUB/cr_$res" ] \\
                   && { ! grep -qx "$ref" "$STUB/needs_crd_patch" || grep -qx "$ref" "$STUB/crd_patched"; }; then
                  :
                else
                  echo "$ref" >> "$STUB/crds.new"
                fi
              done < "$STUB/crds"
              mv "$STUB/crds.new" "$STUB/crds"
              cat "$STUB/crds"
            elif [ -f "$STUB/cr_$1" ]; then
              cat "$STUB/cr_$1"
            fi
            ;;
          delete)
            for ref in "$@"; do
              case "$ref" in --*) ;; *) echo "$ref" >> "$STUB/deleting" ;; esac
            done
            ;;
          patch)
            case "$1" in
              customresourcedefinition*/*) echo "$1" >> "$STUB/crd_patched" ;;
              *)
                res=$1
                name=$2
                grep -v "/$name\\$" "$STUB/cr_$res" > "$STUB/cr.new"
                mv "$STUB/cr.new" "$STUB/cr_$res"
                ;;
            esac
            ;;
        esac
        exit 0
        """;

    @TempDir
    Path stub;

    @BeforeEach
    void setUp() throws IOException {
        for (String file : List.of("crds", "deleting", "undeletable", "needs_crd_patch", "crd_patched", "calls")) {
            Files.writeString(stub.resolve(file), "");
        }
        Path kubectl = stub.resolve("kubectl");
        Files.writeString(kubectl, KUBECTL);
        assertThat(kubectl.toFile().setExecutable(true)).isTrue();
    }

    @Test
    void commandTargetsTheConfiguredGroupsAndEndsWithNewline() {
        String command = CrdCleanup.command();
        assertThat(CrdCleanup.GROUPS)
            .containsExactly("monitoring.coreos.com", "monitoring.grafana.com", "cilium.io");
        assertThat(command).contains("[.](monitoring[.]coreos[.]com|monitoring[.]grafana[.]com|cilium[.]io)$");
        assertThat(command).doesNotContain("@CRD_");
        // uponDestroyRun appends '|| echo FAILED' to any command not ending in a newline
        assertThat(command).endsWith("\n");
    }

    @Test
    void provisionerCommandHasNoUnescapedTerraformInterpolation() {
        String raw = CrdCleanup.command();
        String escaped = CrdCleanup.provisionerCommand();
        // the script does use shell parameter expansion, which Terraform would try to interpolate
        assertThat(raw).contains("${MAX_WAIT}");
        assertThat(escaped).doesNotContainPattern("(?<!\\$)\\$\\{").doesNotContainPattern("(?<!%)%\\{");
        assertThat(escaped.replace("$${", "${").replace("%%{", "%{")).isEqualTo(raw);
    }

    @Test
    void terraformDeliversTheScriptToTheShellVerbatim() throws Exception {
        assumeTrue(onPath("tofu"), "tofu is not installed");
        Path dir = Files.createDirectory(stub.resolve("tf"));
        Files.writeString(dir.resolve("main.tf.json"),
                          "{\"output\":{\"script\":{\"value\":" + jsonString(CrdCleanup.provisionerCommand()) + "}}}");

        // an output-only configuration needs no providers, but parses every string as a template
        assertThat(tofu(dir, "apply", "-auto-approve", "-input=false", "-no-color").exitCode()).isZero();
        Result output = tofu(dir, "output", "-raw", "script");

        assertThat(output.exitCode()).isZero();
        assertThat(output.stdout()).isEqualTo(CrdCleanup.command());
    }

    @Test
    void deletesOnlyTheCrdsOfTheTargetedGroups() throws Exception {
        crds(PROM, SERVICE_MONITORS, GRAFANA_AGENTS, CILIUM_POLICIES, SPARK, EKS_MANAGED, LBC, LOOKALIKE);

        Result result = run(Map.of("CRD_CLEANUP_INTERVAL", "0"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("all the targeted CRDs are gone");
        assertThat(remainingCrds()).containsExactly(SPARK, EKS_MANAGED, LBC, LOOKALIKE);
        // nothing had finalizers, so nothing is patched
        assertThat(calls()).noneMatch(call -> call.startsWith("patch"));
        assertThat(calls()).anyMatch(call -> call.startsWith("delete " + PROM));
    }

    @Test
    void removesFinalizersOfTheResourcesThatHang() throws Exception {
        crds(PROM, CILIUM_IDENTITIES);
        Files.writeString(stub.resolve("cr_prometheuses.monitoring.coreos.com"), "default/k8s\nmonitoring/other\n");
        Files.writeString(stub.resolve("cr_ciliumidentities.cilium.io"), "/12345\n"); // cluster-scoped: no namespace

        Result result = run(Map.of("CRD_CLEANUP_INTERVAL", "0"));

        assertThat(result.exitCode()).isZero();
        assertThat(remainingCrds()).isEmpty();
        assertThat(calls())
            .contains("patch prometheuses.monitoring.coreos.com k8s -n default --type=merge -p {\"metadata\":{\"finalizers\":null}}",
                      "patch prometheuses.monitoring.coreos.com other -n monitoring --type=merge -p {\"metadata\":{\"finalizers\":null}}",
                      "patch ciliumidentities.cilium.io 12345 --type=merge -p {\"metadata\":{\"finalizers\":null}}");
        // the resources' finalizers were enough: the CRD's own were never touched
        assertThat(calls()).noneMatch(call -> call.startsWith("patch customresourcedefinition"));
    }

    @Test
    void removesTheCrdsOwnFinalizersOnlyAfterTheGracePeriod() throws Exception {
        crds(PROM, CILIUM_POLICIES);
        Files.writeString(stub.resolve("needs_crd_patch"), PROM + "\n");

        Result result = run(Map.of("CRD_CLEANUP_INTERVAL", "1", "CRD_CLEANUP_GRACE", "2", "CRD_CLEANUP_MAX_WAIT", "30"));

        assertThat(result.exitCode()).isZero();
        assertThat(remainingCrds()).isEmpty();
        List<String> calls = calls();
        int firstDelete = indexOf(calls, "delete ");
        int crdPatch = indexOf(calls, "patch " + PROM);
        assertThat(firstDelete).isNotNegative();
        assertThat(crdPatch).isGreaterThan(firstDelete);
        // only the stuck CRD is escalated
        assertThat(calls).noneMatch(call -> call.startsWith("patch " + CILIUM_POLICIES));
    }

    @Test
    void givesUpWithAWarningButNeverFailsTheDestroy() throws Exception {
        crds(PROM, CILIUM_POLICIES, SPARK);
        Files.writeString(stub.resolve("undeletable"), PROM + "\n");

        Result result = run(Map.of("CRD_CLEANUP_INTERVAL", "1", "CRD_CLEANUP_GRACE", "1", "CRD_CLEANUP_MAX_WAIT", "3"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).contains("WARNING", "giving up", PROM);
        assertThat(result.stderr()).doesNotContain(CILIUM_POLICIES);
        assertThat(remainingCrds()).containsExactly(PROM, SPARK);
    }

    @Test
    void skipsWhenTheClusterCannotBeReached() throws Exception {
        crds(PROM);
        Files.writeString(stub.resolve("unreachable"), "");

        Result result = run(Map.of("CRD_CLEANUP_INTERVAL", "0"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).contains("cannot list the CRDs");
        assertThat(calls()).noneMatch(call -> call.startsWith("delete"));
    }

    private void crds(String... crds) throws IOException {
        Files.writeString(stub.resolve("crds"), String.join("\n", crds) + "\n");
    }

    private List<String> remainingCrds() throws IOException {
        return Files.readAllLines(stub.resolve("crds")).stream().filter(line -> !line.isBlank()).toList();
    }

    private List<String> calls() throws IOException {
        return Files.readAllLines(stub.resolve("calls"));
    }

    private static int indexOf(List<String> calls, String prefix) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private record Result(int exitCode, String stdout, String stderr) {}

    private static boolean onPath(String executable) {
        for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, executable))) {
                return true;
            }
        }
        return false;
    }

    private static String jsonString(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> json.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return json.append('"').toString();
    }

    private Result tofu(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("tofu"));
        command.addAll(List.of(args));
        return execute(new ProcessBuilder(command).directory(dir.toFile()), dir.resolve("tofu-" + args[0]));
    }

    private Result run(Map<String, String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", CrdCleanup.command());
        builder.environment().putAll(env);
        builder.environment().put("STUB", stub.toString());
        builder.environment().put("PATH", stub + File.pathSeparator + System.getenv("PATH"));
        return execute(builder, stub.resolve("run"));
    }

    private static Result execute(ProcessBuilder builder, Path outputPrefix) throws Exception {
        Path out = Path.of(outputPrefix + ".stdout");
        Path err = Path.of(outputPrefix + ".stderr");
        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("process did not finish within 60s");
        }
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }
}
