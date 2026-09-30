package health.emrys.afon.ops.steps;

import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.cdk.AfonCloudStack;
import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.AZURE_STORAGE_ACCOUNT;
import static health.emrys.afon.config.ConfigUtils.*;
import static java.lang.String.format;

public class StepDocumentation extends StepActions {

    private final PrintWriter out;

    private static final String OPS_PACKAGE = "health.emrys.afon.ops";
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /**
     * The {@code afon-cli.sh} command running the class, without its args, or {@code null} if it has none:
     * <ul>
     *   <li>an ops {@code *Main} class is run by its kebab-named command, eg {@code configure-users} for
     *       {@code ConfigureUsersMain};</li>
     *   <li>a stack is synthesised by {@code validate-stack <stack>}, eg {@code validate-stack aws-full} for
     *       {@code AwsFullStack}.</li>
     * </ul>
     */
    public static String afonCliCommand(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        if (clazz.getName().startsWith(OPS_PACKAGE + ".") && simpleName.endsWith("Main")) {
            return kebabCase(simpleName.substring(0, simpleName.length() - "Main".length()));
        }
        if (AfonCloudStack.class.isAssignableFrom(clazz) && simpleName.endsWith("Stack")) {
            return "validate-stack " + kebabCase(simpleName.substring(0, simpleName.length() - "Stack".length()));
        }
        return null;
    }

    private static String kebabCase(String name) {
        return CAMEL_BOUNDARY.matcher(name).replaceAll("-").toLowerCase();
    }

    public static @NonNull String afonArgumentsToRun(Class clazz,
                                                     String[] args,
                                                     Properties overrides,
                                                     String command) {
        String argLine = "";
        if (args != null && args.length > 0) {
            argLine = format("%s", String.join(" ", args));
            if (!argLine.isEmpty()){
                argLine=format("--exec-args \"%s\"", argLine);
            }
        }
        String overridesArgLine = "";
        if (overrides != null && overrides.size() > 0) {
            overridesArgLine = overrides.entrySet().stream().map(x ->
                format("%s=%s ", x.getKey(), x.getValue())
                ).collect(Collectors.joining());
            if (!overridesArgLine.isEmpty()){
                overridesArgLine=format("--exec-envs \"%s\"", overridesArgLine);
            }
        }
        return format("-a %s --exec-class %s %s %s",
                      command, clazz.getName(), argLine, overridesArgLine);
    }

    public StepDocumentation(OutputStream out) {
        this.out = new PrintWriter(out);
    }

    @Override
    void awsDescribeCertificate(String certArn, String region, OutputStream stdOut) throws ManagedProcessException {
        PrintWriter writer = new PrintWriter(stdOut);
        writer.println("ISSUED");
        writer.flush();
        out.println(format("""
                   If the cert is not issued, run:
                   ```shell
                   aws acm describe-certificate --certificate-arn %s --region %s --queryCertificate.Status --output text
                   ```
                   """, certArn, region));
        out.flush();
    }

    @Override
    public void executeMainOf(Class mainClass, String[] args, Properties overrides) throws Exception {
        String cliCommand = afonCliCommand(mainClass);
        if (cliCommand != null && (overrides == null || overrides.isEmpty())) {
            run("afon-cli.sh", null, args == null ? new String[]{cliCommand} : concat(cliCommand, args));
            return;
        }
        // no afon-cli.sh command runs the class, eg a SparkRunner, or passes it system property overrides
        String fullArgs = afonArgumentsToRun(mainClass, args, overrides, "exec");
        run("./afon.sh", null, fullArgs);
    }

    private static String[] concat(String first, String[] rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    @Override
    public int run(String command, Path path, OutputStream stdOut, String... commands) throws ManagedProcessException {
        if (path != null) {
            out.println(format("In `%s` ", path));
        } else {
            out.println("In the top level directory ");
        }
        out.println(format("""
                   run
                   ```shell
                   %s %s
                   ```
                   """, command, String.join(" ", commands)));
        out.flush();
        return 0;
    }

    @Override
    public Step waitOnDNS(String domain, long timeout) {
        return new Step(stepNameWaitOn(domain), "Wait on DNS", () -> {
            out.println(format("""
                       Wait on DNS for domain until timeout of %s.
                       On a Unix system, running:
                       ```shell
                       dig A %s +short
                       ```
                       will show you the IP address when the DNS servers have updated.
                       """, DurationUtils.formatDuration(timeout), domain));
            out.flush();
        });
    }

    @Override
    @NonNull Path stackDir(StackId stackId) throws IOException {
        Path stackDir = Path.of(ConfigUtils.stackDir(stackId));
        out.println(format("""
                   Stack dir `%s` for stack `%s`.
                   """, stackDir, stackId));
        out.flush();
        return stackDir;
    }

    /**
     * Documents a single stack, standing for each stack in the cloud selected by the filter.
     */
    @Override
    List<String> stackNamesIn(AfonCloud cloud, StackFilter filter) {
        out.println(format("""
                   For each of the %s in cloud `%s`, ie with a stack dir `%s/%s-<stack-type>` or tofu state
                   `%s/terraform.%s-<stack-type>.tfstate`, or the legacy stack dir `%s` or tofu state `%s`:
                   """, filter.description(), cloud, stacksDir(), cloud, ConfigUtils.afonCkdtf(), cloud,
                          legacyStackDir(cloud), legacyTfStatePath(cloud)));
        out.flush();
        return List.of(cloud + "-<stack-type>");
    }

    @Override
    void checkAnswers(String url, String failure) {
        out.println(format("""
                   Check that `%s` answers with 200, eg with:
                   ```shell
                   curl -s -o /dev/null -w '%%{http_code}' %s
                   ```
                   If it doesn't: %s
                   """, url, url, failure));
        out.flush();
    }

    @Override
    boolean exists(Path path) {
        return true;
    }

    @Override
    void checkNotBrokenSymlink(Path path) throws IOException {
        out.println(format("""
                   Check path `%s` is not a broken symlink.
                   """, path));
        out.flush();
    }

    @Override
    void deletePath(Path path) {
        out.println(format("Delete `%s`", path));
        out.flush();
    }

    @Override
    public String azureFirstFile(AfonPropertyProvider properties, String glob, boolean descendingOrder) {
        out.println("Get Azure first file:");
        String     bucket     = properties.getCatalogProperty("AZURE_BUCKET_NAME", "azure");
        String     account    = properties.getValue(AZURE_STORAGE_ACCOUNT);
        return format(
                "abfss://%s@%s.dfs.core.windows.net/%s",
                bucket,
                account,
                "latestName");
    }

    @Override
    public void runSnowflakeSql(AfonPropertyProvider properties, String[] statements) {
        out.println("Run Snowflake SQL:");
        for (String sql : statements) {
            out.println(format(
                    """
                    ```sql
                    %s
                    ```
                    """, sql));
            out.flush();
        }
    }
    
}
