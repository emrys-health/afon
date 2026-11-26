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
 * Runs notebooks in the notebook pod of a user, as the user does, so that the notebooks have the pod's own python, the cluster's own addresses, the network policy of the pod
 * and its certificate of Trino, which they do not have when they run from a machine.  What the runner does is the same for every user: it has the {@link Hub} make the pod, copies
 * each notebook into it, runs it there, brings back the executed notebook and says what failed.  How the pod is made, and whose token it has, is the {@link Hub}'s:
 * <ul>
 *   <li>in project mode, the smoke member: the hub signs them in, as a browser does, which starts their pod with their own token, the one that the hub gives a
 *       member, with the project and the second factor in it;</li>
 *   <li>in non-project mode, the admin: the hub is asked, through its API, to start their pod, which has no token of a sign-in, so the token is given to the notebooks,
 *       in the environment that is passed to {@link #run}.</li>
 * </ul>
 * The environment of the notebooks goes to the pod on the standard input, so that no token is in a command line.  See {@code UserPodNotebookSmokeMain}, which makes it for either.
 */
public final class PodNotebookRunner {

    private static final Logger logger = LoggerFactory.getLogger(PodNotebookRunner.class);

    /** The JupyterHub, as it makes the pod of the user that the notebooks run as: a member who is signed in, or an admin whose pod it is asked to start. */
    public interface Hub {
        /** Stops the user's server, if it has one, so that the next one has the token that it is then given.  A hub that gives the notebooks their token has no need to. */
        void stopServer() throws Exception;

        /** Signs the user in, which has the hub start their server.  A hub that is asked to start the pod instead has nothing to do here. */
        void signIn() throws Exception;

        /** @return the name of the user's pod, once there is one that the sign-in made, or that the hub was asked for, running */
        String awaitPod(Instant since) throws Exception;

        /**
         * @return whether the pod has the user's token from the start, as the hub puts it in a pod at the user's sign-in, which a pod that the hub is asked to start has not:
         * the runner then checks it is there, as the proof that the pod is the sign-in's; if not, the token is given to the notebooks, in their environment
         */
        default boolean podHasTokenOfSignIn() {
            return true;
        }
    }

    /** What is done in the pod. */
    public interface Pod {
        ExecResult exec(String... command) throws Exception;

        /** As {@link #exec(String...)}, with the bytes as the standard input of the command, which are never logged, so may hold a credential. */
        ExecResult exec(byte[] stdin, String... command) throws Exception;

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
     * file to write and the most that a cell may take, in seconds, and what it reads from its standard input is the environment of the notebooks, as JSON.
     */
    static final String RUN = """
        import sys, os, json, nbformat
        from nbclient import NotebookClient
        source, executed, timeout = sys.argv[1], sys.argv[2], int(sys.argv[3])
        # the environment of the notebooks comes on the standard input, as the size of what follows, then that, so that a token is in no command line, and nothing waits for an end
        # of the input that not every exec passes on
        os.environ.update(json.loads(sys.stdin.buffer.read(int(sys.stdin.buffer.readline())).decode("utf-8")))
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
    public PodNotebookRunner(Hub hub, java.util.function.Function<String, Pod> pods, int cellTimeoutSeconds, PrintStream out) {
        this.hub = hub;
        this.pods = pods;
        this.cellTimeoutSeconds = cellTimeoutSeconds;
        this.out = out;
    }

    /**
     * Has the hub make the user's pod, and runs the notebooks in it, each after the other, though one fails.
     *
     * @param environment what is set for the notebooks, eg {@code AFON_TARGET}, and the token, {@code OIDC_TOKEN}, of a user whose pod does not have the token of a sign-in; it goes to the
     *                    pod on the standard input of the command that runs the notebook, so that it is in no command line
     * @param results     where the executed notebooks are written, which it makes
     * @return how each notebook went
     */
    public List<Result> run(List<Path> notebooks, Path results, Map<String, String> environment) throws Exception {
        hub.stopServer();
        Instant since = Instant.now();
        hub.signIn();
        String podName = hub.awaitPod(since);
        Pod pod = pods.apply(podName);
        out.printf("The notebook pod is %s%n", podName);
        if (hub.podHasTokenOfSignIn()) {
            // the token is the proof that the hub's sign-in made the pod, and that it is not one that the hub started with no token, eg by its API: it is not shown
            ExecResult token = pod.exec("sh", "-c", "test -n \"$OIDC_TOKEN\"");
            if (!token.isSuccess()) {
                throw new IllegalStateException("The notebook pod " + podName + " has no OIDC_TOKEN: the hub starts a pod with the member's token only at their sign-in");
            }
        }
        pod.exec("mkdir", "-p", DIR);
        Files.createDirectories(results);
        List<Result> all = new ArrayList<>();
        for (Path notebook : notebooks) {
            all.add(runOne(pod, notebook, results, environment));
        }
        return all;
    }

    /**
     * @return what the script reads from its standard input: the size of the environment, in bytes, as JSON, a line break, and the environment
     */
    static byte[] input(Map<String, String> environment) throws com.fasterxml.jackson.core.JsonProcessingException {
        byte[] json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(environment);
        byte[] size = (json.length + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] all = new byte[size.length + json.length];
        System.arraycopy(size, 0, all, 0, size.length);
        System.arraycopy(json, 0, all, size.length, json.length);
        return all;
    }

    private Result runOne(Pod pod, Path notebook, Path results, Map<String, String> environment) throws Exception {
        String name = notebook.getFileName().toString();
        String executed = name.replace(".ipynb", "") + ".executed.ipynb";
        out.printf("=== Running notebook %s in the pod%n", name);
        pod.copyTo(notebook, DIR + "/" + name);
        ExecResult run = pod.exec(input(environment), "python", "-c", RUN, DIR + "/" + name, DIR + "/" + executed, String.valueOf(cellTimeoutSeconds));
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
