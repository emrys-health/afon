package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.ops.kube.ExecResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs notebooks in the notebook pod of a member, as the member does: the member is signed in to the TRE's JupyterHub, which starts their pod with their token, and
 * the notebooks are run in it, where the cluster's own addresses, the network policy of the pod and the token that the hub gave it are those that the member has.  It is how
 * a project's TRE is smoke tested: nothing runs a notebook from outside the TRE.
 */
public final class NotebookPodRunner {

    private static final Logger logger = LoggerFactory.getLogger(NotebookPodRunner.class);

    /** The hub of the TRE, and the pod that it starts for the member. */
    public interface Hub {
        /** Stops the member's server, if it has one, so that the sign-in starts a new one, with the token of the sign-in. */
        void stopServer() throws Exception;

        /** Signs the member in, which has the hub start their server. */
        void signIn() throws Exception;

        /** @return the name of the member's pod, once there is one that the sign-in made, running */
        String awaitPod(Instant since) throws Exception;
    }

    /** What is done in the pod. */
    public interface Pod {
        ExecResult exec(String... command) throws Exception;

        void copyTo(Path local, String remote) throws Exception;

        void copyFrom(String remote, Path local) throws Exception;
    }

    /** What came of a notebook: whether it passed, and what it printed if it did not. */
    public record Result(String notebook, boolean passed, String output) {
    }

    /** The directory of the pod's home in which the notebooks are put and run. */
    static final String DIR = "/home/jovyan/smoke-notebooks";

    /**
     * What is run in the pod for a notebook, with nbclient's API, which every version of it has, unlike its command, whose options differ: it executes the notebook, whose
     * errors do not stop it, writes the executed notebook, and when a cell failed prints what the notebook printed and the error, and fails.  Its arguments are the notebook, the
     * file to write and the most that a cell may take, in seconds.
     */
    static final String RUN = """
        import sys, nbformat
        from nbclient import NotebookClient
        source, executed, timeout = sys.argv[1], sys.argv[2], int(sys.argv[3])
        notebook = nbformat.read(source, as_version=4)
        NotebookClient(notebook, timeout=timeout, kernel_name="python3", allow_errors=True,
                       resources={"metadata": {"path": source.rsplit("/", 1)[0]}}).execute()
        nbformat.write(notebook, executed)
        cells = [c for c in notebook.cells if c.cell_type == "code"]
        errors = [o for c in cells for o in c.get("outputs", []) if o["output_type"] == "error"]
        if errors:
            for n, cell in enumerate(cells, 1):
                for out in cell.get("outputs", []):
                    if out["output_type"] == "stream":
                        print("".join(out["text"]), end="")
                    elif out["output_type"] == "error":
                        print(f"[cell {n}] {out['ename']}: {out['evalue']}")
            sys.exit(1)
        """;

    private final Hub hub;
    private final java.util.function.Function<String, Pod> pods;
    private final int cellTimeoutSeconds;
    private final PrintStream out;

    /**
     * @param pods               gives what acts on the pod of the name
     * @param cellTimeoutSeconds the most that a cell may take, so that a cell that hangs fails the notebook
     */
    public NotebookPodRunner(Hub hub, java.util.function.Function<String, Pod> pods, int cellTimeoutSeconds, PrintStream out) {
        this.hub = hub;
        this.pods = pods;
        this.cellTimeoutSeconds = cellTimeoutSeconds;
        this.out = out;
    }

    /**
     * Signs the member in to a new server, and runs the notebooks in its pod, each after the other, though one fails.
     *
     * @param environment what is set for the notebooks, eg {@code AFON_TARGET}
     * @param results     where the executed notebooks are written, which it makes
     * @return how each notebook went
     */
    public List<Result> run(List<Path> notebooks, Path results, Map<String, String> environment) throws Exception {
        hub.stopServer();
        Instant since = Instant.now();
        hub.signIn();
        String podName = hub.awaitPod(since);
        Pod pod = pods.apply(podName);
        out.printf("Signed in: the notebook pod is %s%n", podName);
        // the token is the proof that the hub's sign-in made the pod, and that it is not one that the hub started with no token, eg by its API: it is not shown
        ExecResult token = pod.exec("sh", "-c", "test -n \"$OIDC_TOKEN\"");
        if (!token.isSuccess()) {
            throw new IllegalStateException("The notebook pod " + podName + " has no OIDC_TOKEN: the hub starts a pod with the member's token only at their sign-in");
        }
        pod.exec("mkdir", "-p", DIR);
        Files.createDirectories(results);
        List<Result> all = new ArrayList<>();
        for (Path notebook : notebooks) {
            all.add(runOne(pod, notebook, results, environment));
        }
        return all;
    }

    private Result runOne(Pod pod, Path notebook, Path results, Map<String, String> environment) throws Exception {
        String name = notebook.getFileName().toString();
        String executed = name.replace(".ipynb", "") + ".executed.ipynb";
        out.printf("=== Running notebook %s in the pod%n", name);
        pod.copyTo(notebook, DIR + "/" + name);
        List<String> command = new ArrayList<>(List.of("env"));
        environment.forEach((key, value) -> command.add(key + "=" + value));
        command.addAll(List.of("python", "-c", RUN, DIR + "/" + name, DIR + "/" + executed, String.valueOf(cellTimeoutSeconds)));
        ExecResult run = pod.exec(command.toArray(String[]::new));
        try {
            pod.copyFrom(DIR + "/" + executed, results.resolve(name));
        } catch (Exception e) {
            logger.warn("Could not copy the executed notebook {} out of the pod: {}", name, e.getMessage());
        }
        if (run.isSuccess()) {
            out.printf("=== Notebook %s passed%n", name);
            return new Result(name, true, "");
        }
        // what the notebook printed, or, if it could not be run at all, what python said, as nothing else would tell
        String output = run.stdout().isBlank() ? run.stderr().strip() : run.stdout();
        out.printf("=== Notebook %s FAILED%n%s%n", name, output);
        return new Result(name, false, output);
    }
}
