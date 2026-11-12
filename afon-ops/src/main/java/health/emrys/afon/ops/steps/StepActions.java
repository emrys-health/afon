package health.emrys.afon.ops.steps;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessBuilder;
import ch.vorburger.exec.ManagedProcessException;
import health.emrys.afon.ops.scripts.ProcessLogLevels;
import health.emrys.afon.ops.scripts.ScriptMain;
import com.azure.storage.blob.models.BlobItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.CloudStackValidator;
import health.emrys.afon.cdk.azure.AzureReader;
import health.emrys.afon.config.*;
import health.emrys.afon.ops.ConfigureUsersMain;
import health.emrys.afon.ops.ManagePolarisCatalogsMain;
import health.emrys.afon.ops.RegisterPolarisTableMain;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.kube.tools.KeyCloakTunnel;
import health.emrys.afon.ops.kube.tools.PolarisWhitelister;
import health.emrys.afon.ops.kube.tools.PortForwarder;
import health.emrys.afon.ops.spark.SparkRunner;
import health.emrys.afon.ops.synth.PlanGuards;
import health.emrys.afon.snowflake.SnowflakeConnectionFactory;
import org.apache.commons.io.file.PathUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static health.emrys.afon.config.AfonProperty.AZURE_STORAGE_ACCOUNT;
import static health.emrys.afon.config.ConfigUtils.*;
import static health.emrys.afon.ops.ManagePolarisCatalogsMain.*;
import static health.emrys.afon.ops.steps.DurationUtils.formatDuration;
import static java.lang.String.format;
import static java.util.Comparator.comparing;
import static org.slf4j.LoggerFactory.getLogger;

public class StepActions {

    private static final Logger logger = getLogger(StepActions.class);

    /** The checks of the synthesised plan of a stack, before it is deployed. */
    protected PlanGuards planGuards = new PlanGuards();

    /**
     * Deletes the stacks in the cloud selected by the filter, allowing for a single attempt.
     * @param outputStream the stream to which the tofu output is written
     * @param beforeDeleting called with each stack, just before it is deleted, eg to validate its plan, see
     *                       {@link StepOperations#validatePlanBeforeDeleting(StackId)}
     * @throws IOException
     */
    void deleteStacksIn(AfonCloud cloud, StackFilter filter, OutputStream outputStream,
                        Consumer<StackId> beforeDeleting) throws IOException {
        deleteStacksIn(cloud, filter, 1, outputStream, beforeDeleting, stackId -> { });
    }

    /**
     * Deletes the stacks in the cloud selected by the filter, allowing for a single attempt.
     * @param beforeDeleting called with each stack, just before it is deleted
     * @param afterDeleting called with each stack with a stack id, just after it was deleted, eg to delete its stored state,
     *                      see {@link #deleteStateFromStore}
     */
    void deleteStacksIn(AfonCloud cloud, StackFilter filter, OutputStream outputStream,
                        Consumer<StackId> beforeDeleting, Consumer<StackId> afterDeleting) throws IOException {
        deleteStacksIn(cloud, filter, 1, outputStream, beforeDeleting, afterDeleting);
    }

    /**
     * Deletes the stacks in the cloud selected by the filter, among the stacks with a stack dir or a tofu state,
     * allowing a given number of retries.  These include the legacy stack, whose dir and tofu state were shared by
     * all the stacks of the cloud, named as the cloud.
     * @param outputStream the stream to which the tofu output is written
     * @param beforeDeleting called with each stack with a stack id, just before it is deleted
     * @throws IOException
     */
    void deleteStacksIn(AfonCloud cloud, StackFilter filter, int maxAttempts, OutputStream outputStream,
                        Consumer<StackId> beforeDeleting) throws IOException {
        deleteStacksIn(cloud, filter, maxAttempts, outputStream, beforeDeleting, stackId -> { });
    }

    /**
     * As above, calling {@code afterDeleting} with each stack with a stack id, just after it was deleted.
     */
    void deleteStacksIn(AfonCloud cloud, StackFilter filter, int maxAttempts, OutputStream outputStream,
                        Consumer<StackId> beforeDeleting, Consumer<StackId> afterDeleting) throws IOException {
        checkNotBrokenSymlink(Path.of(ConfigUtils.afonCkdtf()));
        List<String> stackNames = stackNamesIn(cloud, filter);
        // only a deployed stack, ie with a tofu state, may need keeping: a stack dir without one is just deleted
        checkNoRefusal(filter, deployedStackNames(stackNames, Path.of(ConfigUtils.afonCkdtf())));
        if (stackNames.isEmpty()) {
            logger.info("No stack to delete in cloud {}, as selected by: {}", cloud, filter.description());
        }
        for (String stackName : stackNames) {
            logger.info("Deleting stack {}, as selected by: {}", stackName, filter.description());
            if (stackName.equals(cloud.toString())) {
                deleteStack(Path.of(legacyStackDir(cloud)), Path.of(legacyTfStatePath(cloud)), maxAttempts, outputStream);
            } else {
                deleteStackAndItsStore(StackId.parse(stackName), maxAttempts, outputStream, beforeDeleting, afterDeleting);
            }
        }
    }

    /**
     * Deletes the stack, then calls {@code afterDeleting} with it if it had a tofu state, ie if it was deployed from here.  A stack without
     * one, eg whose plan was only synthesised by a run that stopped before deploying, has nothing to delete in the store either: what is
     * stored, if anything, is the state of a deployment made from elsewhere, which this delete did not touch, and which is the only record
     * of what to destroy.
     */
    void deleteStackAndItsStore(StackId stackId, int maxAttempts, OutputStream outputStream, Consumer<StackId> beforeDeleting,
                                Consumer<StackId> afterDeleting) throws IOException {
        beforeDeleting.accept(stackId);
        boolean deployed = exists(Path.of(tfStatePath(stackId)));
        deleteStack(Path.of(ConfigUtils.stackDir(stackId)), Path.of(tfStatePath(stackId)), maxAttempts, outputStream);
        // the kubeconfig of the stack's cluster, which is now gone
        deletePath(Path.of(kubeConfigPath(stackId)));
        removeCurrentKubeConfigIfNamed(stackId);
        if (deployed) {
            afterDeleting.accept(stackId);
        } else {
            logger.info("Stack {} had no tofu state, so its stored state, if any, is left", stackId);
        }
    }

    /**
     * Checks, before deleting any, that none of the selected deployed stacks must be kept, unless {@code --replace}
     * is given.
     * @param stackNames the names of the selected stacks that are deployed, ie with a tofu state
     * @throws IllegalStateException naming the stacks refused, and why
     */
    protected void checkNoRefusal(StackFilter filter, List<String> stackNames) {
        List<StackFilter.Refusal> refusals = stackNames.stream().map(filter.refusal()).filter(Objects::nonNull).toList();
        if (refusals.isEmpty()) {
            return;
        }
        String deploying = filter.deploying() == null ? "the stack" : filter.deploying().toString();
        String them = refusals.size() == 1 ? "it" : "them";
        List<String> deleteCommands = refusals.stream().map(StackFilter.Refusal::deleteCommand)
                                              .filter(Objects::nonNull).distinct().toList();
        StringBuilder message = new StringBuilder(format("Cannot deploy %s, as %s.%n", deploying,
            refusals.stream().map(StackFilter.Refusal::reason).collect(Collectors.joining(";\nand "))));
        if (!deleteCommands.isEmpty()) {
            message.append(format("To deploy %s, delete %s first:%n", deploying, them));
            deleteCommands.forEach(command -> message.append(format("    %s%n", command)));
            message.append(format("or rerun with --replace to delete %s as part of this run.", them));
        } else {
            message.append(format("To deploy %s, rerun with --replace to delete %s as part of this run.", deploying, them));
        }
        throw new IllegalStateException(message.toString());
    }

    /**
     * Returns the names of the stacks in the cloud selected by the filter, see {@link #stackNamesIn(AfonCloud)}.
     */
    protected List<String> stackNamesIn(AfonCloud cloud, StackFilter filter) throws IOException {
        return stackNamesIn(cloud).stream().filter(filter.selects()).toList();
    }

    /**
     * Returns the names of the stacks in the cloud, ie of the stack dirs and tofu states found in the cdktf dir,
     * eg {@code <stack-id>} for {@code stacks/<stack-id>} or {@code terraform.<stack-id>.tfstate},
     * and {@code aws} for the legacy {@code stacks/aws} or {@code terraform.aws.tfstate}.
     * <p>
     * A name is not necessarily a stack id, see {@link StackId}: it may also be that of the cloud, for the legacy stack, or of a
     * stack that is no longer defined.
     */
    List<String> stackNamesIn(AfonCloud cloud) throws IOException {
        return stackNamesIn(cloud, Path.of(ConfigUtils.afonCkdtf()));
    }

    /**
     * @return the names of the stacks that are deployed, ie with a tofu state in the cdktf dir, eg
     * {@code terraform.<stack-id>.tfstate} for {@code <stack-id>}, or {@code terraform.aws.tfstate} for the legacy
     * {@code aws}, rather than just a stack dir, eg left from synthesising a stack without deploying it
     */
    List<String> deployedStackNames(List<String> stackNames, Path cdktfDir) {
        return stackNames.stream()
                         .filter(name -> exists(cdktfDir.resolve(TF_STATE_PREFIX + name + TF_STATE_SUFFIX)))
                         .toList();
    }

    static List<String> stackNamesIn(AfonCloud cloud, Path cdktfDir) throws IOException {
        Set<String> stackNames = new TreeSet<>();
        Path stacksDir = cdktfDir.resolve(STACKS_DIR_NAME);
        if (Files.isDirectory(stacksDir)) {
            try (var dirs = Files.list(stacksDir)) {
                dirs.filter(Files::isDirectory)
                    .map(dir -> dir.getFileName().toString())
                    .filter(name -> isStackNameIn(cloud, name))
                    .forEach(stackNames::add);
            }
        }
        if (Files.isDirectory(cdktfDir)) {
            try (var files = Files.list(cdktfDir)) {
                files.map(file -> file.getFileName().toString())
                     .filter(name -> name.startsWith(TF_STATE_PREFIX) && name.endsWith(TF_STATE_SUFFIX))
                     .map(name -> name.substring(TF_STATE_PREFIX.length(), name.length() - TF_STATE_SUFFIX.length()))
                     .filter(name -> isStackNameIn(cloud, name))
                     .forEach(stackNames::add);
            }
        }
        return new ArrayList<>(stackNames);
    }

    private static final String STACKS_DIR_NAME = "stacks";
    private static final String TF_STATE_PREFIX = "terraform.";
    private static final String TF_STATE_SUFFIX = ".tfstate";

    private static boolean isStackNameIn(AfonCloud cloud, String name) {
        return name.equals(cloud.toString()) || name.startsWith(cloud + "-");
    }

    /**
     * Deletes the stack by running {@code tofu destroy} in its dir, if it has a tofu state, then deleting
     * its dir and tofu state.
     * @throws IllegalStateException if the stack has a tofu state but no dir, as tofu cannot destroy it
     */
    void deleteStack(Path stackDir, Path tfState, int maxAttempts, OutputStream outputStream) throws IOException {
        if (!exists(tfState)) {
            logger.info("No tofu state {}, so nothing deployed from stack dir {}", tfState, stackDir);
            deletePath(stackDir);
            return;
        }
        if (!exists(stackDir)) {
            throw new IllegalStateException(String.format("""
                Found tofu state %s but no stack dir %s, so tofu cannot destroy the stack.
                Recreate the stack dir by validating the stack, ie with the same config:
                    afon-cli.sh validate-stack <stack-id>
                and run again.
                If you're sure nothing of the stack is still running, delete the tofu state %s.
                """, tfState, stackDir, tfState));
        }
        try {
            tofuDestroy(stackDir, maxAttempts, outputStream);
            checkNothingLeft(stackDir, tfState);
            deletePath(stackDir);
            deletePath(tfState);
        } catch (ManagedProcessException e) {
            String message = String.format("""
                Failed to run, after %s attempt(s):
                    tofu destroy
                Your environment may be in an unstable state.
                It may be that something like a Helm chart is blocking the destroy.
                If so, remove it with something like:
                    tofu state rm 'helm_release.prometheusRelease'
                and run again:
                    tofu destroy
                
                If you're sure nothing in the environment is still running, delete the tofu dir %s.
                """, maxAttempts, stackDir);
            logger.error(message, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Checks, before the tofu state and the stack dir are deleted, that the state lists no resource that is still deployed, other than
     * those that outlive the stack, see {@link #retainedExclusions}.  {@code tofu destroy} can finish successfully without having
     * destroyed what is in the state, eg saying {@code 0 destroyed}; deleting the state then leaves those resources in the cloud
     * with nothing to destroy them, and a redeploy fails because they exist.  The state itself is what is checked, not what tofu says.
     *
     * @throws IllegalStateException if the state still has such resources, or cannot be read, naming them: nothing is deleted
     */
    protected void checkNothingLeft(Path stackDir, Path tfState) {
        List<String> kept = retainedExclusions(stackDir).stream().map(exclusion -> exclusion.substring("-exclude=".length())).toList();
        List<String> left = managedResourcesLeft(tfState, kept);
        if (left.isEmpty()) {
            return;
        }
        throw new IllegalStateException(String.format("""
            tofu destroy finished, but the tofu state %s still has %d resource(s) that are deployed:
                %s%s
            Neither the state nor the stack dir %s is deleted, as that would leave them in the cloud with nothing to destroy them.
            Run the delete again. If they are still there, find out why in the stack dir:
                tofu state list
                tofu destroy
            If you know they are gone from the cloud, remove them from the state with 'tofu state rm <address>' and run again.
            """, tfState, left.size(), String.join("\n    ", left.stream().limit(15).toList()),
            left.size() > 15 ? "\n    ... and " + (left.size() - 15) + " more" : "", stackDir));
    }

    /**
     * @param kept the addresses of the resources that outlive the stack, eg {@code aws_ebs_volume.retained_gitea_volume}
     * @return the addresses of the resources that the state lists as deployed, except those kept: data sources are not resources of
     * the cloud, and an entry without instances is deployed nowhere.  An empty state file is an empty state, as it is for tofu.
     * @throws IllegalStateException if the state cannot be read
     */
    static List<String> managedResourcesLeft(Path tfState, List<String> kept) {
        List<String> left = new ArrayList<>();
        try {
            String content = Files.readString(tfState);
            if (content.isBlank()) {
                return left;
            }
            for (JsonNode resource : new ObjectMapper().readTree(content).path("resources")) {
                if (!"managed".equals(resource.path("mode").asText("managed")) || resource.path("instances").isEmpty()) {
                    continue;
                }
                String module  = resource.path("module").asText("");
                String address = (module.isEmpty() ? "" : module + ".") + resource.path("type").asText() + "." + resource.path("name").asText();
                if (!kept.contains(address)) {
                    left.add(address);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the tofu state " + tfState + " to check that nothing is left of the stack, "
                                            + "so it is not deleted: " + e.getMessage(), e);
        }
        return left;
    }

    protected boolean exists(Path path) {
        return Files.exists(path);
    }

    protected @NonNull Path stackDir(StackId stackId) throws IOException {
        checkNotBrokenSymlink(Path.of(ConfigUtils.afonCkdtf()));
        Path stackDir = Path.of(ConfigUtils.stackDir(stackId));
        logger.info("Using stack dir {}", stackDir);
        if (!Files.exists(stackDir)){
            Files.createDirectories(stackDir);
            logger.info("Created new stack dir {}, as it did not exist.", stackDir);
        }
        return stackDir;
    }

    protected void checkNotBrokenSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path) && !Files.exists(path)) {
            throw new IOException(String.format(
                "%s is a symlink pointing to %s, which does not exist. Fix or recreate the symlink target.",
                path, Files.readSymbolicLink(path)));
        }
    }

    /**
     * Runs {@code tofu init}, and, only if that fails because the stack dir locks provider versions that the plan no longer
     * pins, runs {@code tofu init -upgrade}.  A stack dir outlives the plans synthesised into it, and a newer plan may pin
     * another version, eg of the azurerm provider, which {@code tofu init} then refuses with "locked provider ... does not match
     * configured version constraint ...; must use tofu init -upgrade".  Every other failure, eg of the network, is not retried.
     * Upgrading only then keeps a normal run free of the registry and of changes to the lock file.
     *
     * @return the exit value of the last {@code tofu init}
     * @throws ManagedProcessException if it fails, as {@link #run} does for a process that exits with an error, with the output
     *                                 of the process, stdout and stderr, in its message, which is where the mismatch is found
     */
    int tofuInit(Path tofuDir, OutputStream outputStream) throws ManagedProcessException {
        try {
            return run("tofu", tofuDir, outputStream, "init");
        } catch (ManagedProcessException e) {
            if (!isProviderLockMismatch(e.getMessage())) {
                throw e;
            }
            logger.info("The providers locked in {} do not match those the plan pins, upgrading them", tofuDir);
            return run("tofu", tofuDir, outputStream, "init", "-upgrade");
        }
    }

    /**
     * @return whether the output of {@code tofu init} says to run it with {@code -upgrade}, ie the lock file of the stack dir
     * does not match the versions the plan pins.  The message may be wrapped by the terminal, boxed with {@code │}, or coloured,
     * so those are ignored.
     */
    static boolean isProviderLockMismatch(String output) {
        String plain = output.replaceAll("\u001B\\[[0-9;]*m", "").replaceAll("[│\\s]+", " ");
        return plain.contains("init -upgrade");
    }

    private void tofuDestroy(Path tofuDir, int maxAttempts, OutputStream outputStream) throws ManagedProcessException {
        logger.info("Executing tofu destroy in dir {}", tofuDir);
        tofuInit(tofuDir, outputStream);
        List<String> destroy = new ArrayList<>(List.of("destroy", "--auto-approve"));
        List<String> exclusions = retainedExclusions(tofuDir);
        if (!exclusions.isEmpty()) {
            logger.info("Keeping what outlives the stack: {}", exclusions);
        }
        destroy.addAll(exclusions);
        String[] args = destroy.toArray(new String[0]);
        tofuWithRetry(tofuDir, outputStream, maxAttempts, args);
    }

    /**
     * Removes the {@code current.config} link if it names the kubeconfig of the stack, now gone, so that it does not dangle.
     * A link to another stack's kubeconfig is left alone.
     */
    protected void removeCurrentKubeConfigIfNamed(StackId stackId) {
        Path current = Path.of(currentKubeConfigPath());
        try {
            if (Files.isSymbolicLink(current) && Files.readSymbolicLink(current).equals(Path.of(kubeConfigPath(stackId)))) {
                logger.info("Removing {}, as it named the kubeconfig of the deleted stack {}", current, stackId);
                Files.delete(current);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void deletePath(Path path) {
        if (!exists(path)) {
            return;
        }
        logger.info("Deleting path {}", path);
        try {
            PathUtils.delete(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Synthesises the plan of the stack into its stack dir, from the config.
     * @param properties the config, which the caller has already, so that every step is validated against the same
     * @param users the users, which the caller has already
     * @throws RuntimeException if the stack fails to synthesise, eg because of a missing property
     */
    protected void synthesiseStack(StackId stackId, AfonPropertyProvider properties, AfonUserProvider users) throws Exception {
        new CloudStackValidator().validate(stackId, properties, users);
    }

    /**
     * Starts the stack, allowing for a single attempt.
     * @param outputStream the stream to which the tofu output is written
     * @throws Exception
     */
    void startStack(StackId stackId, OutputStream outputStream) throws Exception {
        startStack(stackId, 1, outputStream);
    }

    /**
     * Starts the stack, attempting for a given number of retries, in the stack dir of its {@link StackId}, whose plan must have been synthesised there, see {@link #synthesiseStack}.
     * @param outputStream the stream to which the tofu output is written
     * @throws Exception
     */
    void startStack(StackId stackId, int maxAttempts, OutputStream outputStream) throws Exception {
        startManagedStack(stackId, maxAttempts, outputStream);
    }

    private void startManagedStack(StackId stackId, int maxAttempts, OutputStream outputStream) throws IOException {
        // The kubernetes/helm providers are configured from the managed cluster's endpoint and
        // credentials (EKS endpoint+token on AWS, the AKS kubeconfig on Azure). With the v3
        // (plugin-framework) providers, those must be known at plan time, otherwise apply fails
        // with "Provider produced inconsistent final plan" (the provider can't be configured
        // until the cluster exists). So we bootstrap ONLY the cluster in a targeted first phase;
        // the second, full apply then plans the k8s/helm resources against a cluster that
        // already exists.
        //
        // We deliberately target only the control plane, not the worker nodes. On AWS the node
        // group has no CNI of its own and only becomes Ready once Cilium (a Helm release) is
        // installed, so it must stay in phase two alongside Cilium; the EKS cluster resource
        // does not include the node group. On Azure the default node pool is declared inline on
        // the cluster resource (and uses AKS-managed Cilium), so targeting the cluster brings up
        // the nodes too. On GCP the default node pool is likewise part of the
        // google_container_cluster resource and uses GKE's built-in CNI, so the targeted apply
        // yields a Ready cluster whose endpoint and CA cert are then known for phase two.
        // Stacks without a managed cluster yield no targets and skip straight to the full apply.
        if (stackId.cloud() == AfonCloud.AZURE) {
            logAzureDeployer();
        }
        Path stackDir = stackDir(stackId);
        if (stackId.cloud() == AfonCloud.AWS) {
            planGuards.checkNoOrphanAwsVpc(stackDir, Path.of(tfStatePath(stackId)));
        }
        tofuApply(stackDir, clusterBootstrapTargets(stackDir), maxAttempts, outputStream);
    }

    /**
     * Phase one of {@link #startStack(StackId, int, OutputStream)} alone: brings up only the managed cluster of the stack, in the stack dir
     * of its {@link StackId}, whose plan must have been synthesised there, so that something that needs the cluster, and not the rest of the
     * stack, can be done before {@link #startStackAfterCluster}.  Eg the federated credential of the Azure app, which trusts the OIDC issuer
     * of the cluster, and so needs it to exist, which Polaris needs when it starts.
     */
    void startCluster(StackId stackId, OutputStream outputStream) throws Exception {
        if (stackId.cloud() == AfonCloud.AZURE) {
            logAzureDeployer();
        }
        Path stackDir = stackDir(stackId);
        if (tofuInit(stackDir, outputStream) != 0) {
            throw new ManagedProcessException(format("tofu init failed in %s", stackDir));
        }
        tofuApplyBootstrap(stackDir, clusterBootstrapTargets(stackDir), 1, outputStream);
    }

    /**
     * Phase two of {@link #startStack(StackId, int, OutputStream)} alone: the full apply, after {@link #startCluster}, which may be run by another
     * process, on another machine, eg another agent of a pipeline, with the state and the plan restored from the store: so it initialises the stack dir too.
     */
    void startStackAfterCluster(StackId stackId, OutputStream outputStream) throws Exception {
        Path stackDir = stackDir(stackId);
        // as the first phase does, as the resume may be on a machine that only has what the store restored, ie the state and the plan, not the providers
        if (tofuInit(stackDir, outputStream) != 0) {
            throw new ManagedProcessException(format("tofu init failed in %s", stackDir));
        }
        tofuApplyAll(stackDir, 1, outputStream);
    }

    /**
     * Gives the app of Azure the federated credential of the cluster of the stack, which Polaris, deployed next, needs to get a token, and which
     * needs the cluster to exist, as it trusts the issuer of the cluster.  The deployer must be allowed to change the app registration, ie have
     * the app registration role, so that the build does not go back to the admin: if not, it fails, saying why, see {@link StepOperations#ensureFederatedCredential}.
     *
     * @throws IllegalStateException if the deployer could not give it
     */
    protected void ensureFederatedCredential(StackId stackId) {
        try {
            giveAppFederatedCredential(stackId);
            logger.info("The app has the federated credential of the cluster of {}", stackId);
        } catch (RuntimeException e) {
            throw new IllegalStateException(format("""
                The deployer could not give the app the federated credential of the cluster of %1$s, which Polaris needs to get a token (AZURE_WORKLOAD_IDENTITY=true), see the error above.
                The deployer must be allowed to change the app registration, ie have the app registration role, which the admin grants once, as the credential is new for each cluster:
                  afon-cli.sh azure-identity-admin grant [--deployer <user principal name>]
                (a service principal also needs Application.ReadWrite.OwnedBy of Microsoft Graph).  Then run the same command again: the steps before are idempotent.
                Whether the app has it, and what it is:  afon-cli.sh azure-identity %1$s""", stackId), e);
        }
    }

    /**
     * Runs {@code azure-identity true}, which gives the app the federated credential of the cluster of the stack, replacing that of a previous build of
     * the cluster.
     *
     * @throws RuntimeException if it fails, eg because the deployer may not change the app registration
     */
    protected void giveAppFederatedCredential(StackId stackId) {
        new ScriptMain("azure/identity.sh").run("true", stackId.toString());
    }

    /**
     * Logs the kind of identity that deploys to Azure, ie a user, a service principal or a managed identity, so that a
     * failure to grant it a role, eg cluster admin, is read against who is deploying.  Informational only: if the identity
     * cannot be read, eg because {@code az} is not logged in, it is logged and the deployment goes on.
     */
    private void logAzureDeployer() {
        try {
            // read from the process directly, as the output given to run() is pumped asynchronously, and may still be empty
            Process process = new ProcessBuilder("az", "account", "show", "--query", "[user.type, user.name]", "--output", "tsv")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                logger.warn("Could not determine the identity deploying to Azure: az account show exited with {}", exit);
                return;
            }
            logger.info("Deploying to Azure as {}", describeAzureDeployer(output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Could not determine the identity deploying to Azure: {}", e.getMessage());
        }
    }

    /**
     * @param accountUser the {@code user.type} and {@code user.name} of {@code az account show}, one per line
     * @return a description of the deployer, eg {@code user user@example.com} or {@code servicePrincipal <app id>}
     */
    static String describeAzureDeployer(String accountUser) {
        String[] lines = accountUser.strip().split("\\R");
        String type = lines[0].strip();
        String name = lines.length > 1 ? lines[1].strip() : "";
        if (type.isEmpty() || type.equals("null")) {
            return "an unknown identity";
        }
        return name.isEmpty() || name.equals("null") ? type : type + " " + name;
    }

    private void tofuApply(Path tofuDir, List<String> bootstrapTargets, int maxAttempts, OutputStream outputStream) throws IOException {
        if (tofuInit(tofuDir, outputStream) != 0) {
            throw new ManagedProcessException(format("tofu init failed in %s", tofuDir));
        }
        tofuApplyBootstrap(tofuDir, bootstrapTargets, maxAttempts, outputStream);
        tofuApplyAll(tofuDir, maxAttempts, outputStream);
    }

    /** Phase one: only the targets, ie the managed cluster, if there are any */
    private void tofuApplyBootstrap(Path tofuDir, List<String> bootstrapTargets, int maxAttempts, OutputStream outputStream) throws ManagedProcessException {
        if (!bootstrapTargets.isEmpty()) {
            List<String> phaseOne = new ArrayList<>(List.of("apply", "--auto-approve", "-parallelism=5"));
            phaseOne.addAll(bootstrapTargets);
            logger.info("Bootstrapping targets before the full apply: {}", phaseOne);
            tofuWithRetry(tofuDir, outputStream, maxAttempts, phaseOne.toArray(new String[0]));
        }
    }

    /** Phase two: the full apply */
    private void tofuApplyAll(Path tofuDir, int maxAttempts, OutputStream outputStream) throws ManagedProcessException {
        tofuWithRetry(tofuDir, outputStream, maxAttempts, "apply", "--auto-approve", "-parallelism=5");
    }

    /**
     * Retries a {@code tofu} invocation on failure.
     * Retrying is safe because {@code tofu} re-plans against current state each time.
     */
    private void tofuWithRetry(Path tofuDir, OutputStream outputStream, int maxAttempts, String... args) throws ManagedProcessException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                int exitCode = run("tofu", tofuDir, outputStream, args);
                if (exitCode == 0) {
                    return;
                }
                throwOrRetry(args, attempt, maxAttempts, new ManagedProcessException(String.format(
                    "tofu %s exited with code %d", String.join(" ", args), exitCode)));
            } catch (ManagedProcessException e) {
                // ManagedProcess.waitForExit() throws on a non-zero exit code rather than returning it,
                // so a failed `tofu` invocation surfaces here, not via the exitCode check above.
                throwOrRetry(args, attempt, maxAttempts, e);
            }
        }
    }

    /**
     * Throws failure exception if the attempts have reached its max number allowed,
     * else waits for 30s and carries on to the following attempt.
     * @throws ManagedProcessException
     */
    private void throwOrRetry(String[] args, int attempt, int maxAttempts, ManagedProcessException failure) throws ManagedProcessException {
        if (attempt == maxAttempts) {
            throw failure;
        }
        final Duration duration = Duration.ofSeconds(30);
        logger.warn("tofu {} failed on attempt {}/{}, retrying in {}s",
                 String.join(" ", args), attempt, maxAttempts, duration.toSeconds(), failure);
        sleepUninterruptibly(duration);
    }

    private void sleepUninterruptibly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void executeMainOf(Class mainClass, String[] args) throws Exception {
        executeMainOf(mainClass, args, new Properties());
    }

    protected void executeMainOf(Class mainClass, String[] args, Properties overrides) throws Exception {
        logger.info("Running {} with arguments {} and overrides {}",
                 mainClass.getName(),
                 String.join(" ", args),
                 overrides);

        Properties original = setSystemProperties(overrides);
        try {
            Method main = mainClass.getDeclaredMethod("main", String[].class);
            String[] mainArgs = Arrays
                .stream(args)
                .filter(s -> s != null && !s.isEmpty())
                .toArray(String[]::new);
            main.invoke(null, (Object) mainArgs);
        } finally {
            restoreSystemProperties(overrides, original);
        }
    }

    private @NonNull Properties setSystemProperties(Properties properties) {
        Properties original = new Properties();
        properties.keySet().forEach(mapKey -> {
            String key        = mapKey.toString();
            String property   = System.getProperty(key);
            if (property != null) {
                original.put(key, property);
            }
            String value = properties.getProperty(key);
            logger.info("Setting system property {} = {}", key, value);
            System.setProperty(key, value);
        });
        return original;
    }

    private void restoreSystemProperties(Properties properties, Properties original) {
        properties.keySet().forEach(mapKey -> {
            String key   = mapKey.toString();
            String value = original.getProperty(key);
            logger.info("Restoring system property {} = {}", key, value);
            if (value != null) {
                System.setProperty(key, value);
            }  else {
                System.clearProperty(key);
            }
        });
    }

    /**
     * Terraform resource types for the managed Kubernetes control planes whose endpoint and
     * credentials the kubernetes/helm providers depend on at plan time. We bootstrap these in a
     * targeted first apply phase. GCP is included even though it authenticates via an exec plugin:
     * that plugin only supplies the auth token on demand, but the provider's {@code host} and
     * {@code cluster_ca_certificate} are still read from the {@code google_container_cluster}
     * resource, so the cluster must exist at plan time to avoid an inconsistent final plan.
     */
    private static final List<String> MANAGED_CLUSTER_RESOURCE_TYPES =
        List.of("aws_eks_cluster", "azurerm_kubernetes_cluster", "google_container_cluster");

    /**
     * Reads config file {@code cdk.tf.json} in the given stack dir and returns {@code -target=...}
     * arguments for any managed Kubernetes cluster it declares, so the cluster can be created in
     * a first apply phase. This makes the cluster endpoint/credentials known at plan time for the
     * second, full apply, which is what the v3 kubernetes/helm providers require. Returns an
     * empty list (and logs nothing fatal) when the file is absent or declares no such cluster.
     */
    private List<String> clusterBootstrapTargets(Path stackDir) {
        List<String> targets = new ArrayList<>();
        Path config = stackDir.resolve("cdk.tf.json");
        if (!config.toFile().exists()) {
            return targets;
        }
        try {
            JsonNode resources = new ObjectMapper().readTree(config.toFile()).path("resource");
            for (String type : MANAGED_CLUSTER_RESOURCE_TYPES) {
                resources.path(type).fieldNames()
                         .forEachRemaining(name -> targets.add("-target=" + type + "." + name));
            }
        } catch (IOException e) {
            logger.warn("Could not read config file {} to compute cluster bootstrap targets; "
                     + "proceeding with a single apply", config, e);
        }
        return targets;
    }

    /**
     * Reads config file {@code cdk.tf.json} in the given stack dir and returns {@code -exclude=...} arguments for every
     * resource with {@code prevent_destroy}, eg a volume that outlives the cluster, so that destroying the stack keeps
     * them, while a plain {@code tofu destroy} fails rather than deletes them.  What they depend on is kept with them,
     * what depends on them is destroyed.
     *
     * @throws IllegalStateException if the file cannot be read, as destroying without the exclusions would delete them
     */
    List<String> retainedExclusions(Path stackDir) {
        List<String> exclusions = new ArrayList<>();
        Path config = stackDir.resolve("cdk.tf.json");
        if (!config.toFile().exists()) {
            return exclusions;
        }
        try {
            JsonNode resources = new ObjectMapper().readTree(config.toFile()).path("resource");
            resources.fields().forEachRemaining(type -> type.getValue().fields().forEachRemaining(resource -> {
                if (resource.getValue().path("lifecycle").path("prevent_destroy").asBoolean(false)) {
                    exclusions.add("-exclude=" + type.getKey() + "." + resource.getKey());
                }
            }));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + config + " for what outlives the stack", e);
        }
        return exclusions;
    }

    /** The tag of the snapshot of the state and plan of a stack in the store, see the command cdktf-store. */
    public static final String STORE_TAG = "current";

    /**
     * Stores the tofu state and plan of the stack in the object storage of its cloud, replacing what is there, as
     * {@code cdktf-store push} does.  It is only a warning if it fails, as the state is intact on this machine and can be stored
     * again with the command.  Does nothing if the stack has no tofu state.
     */
    protected void pushStateToStore(StackId stackId) {
        if (!exists(Path.of(tfStatePath(stackId)))) {
            logger.info("No tofu state of stack {}, so nothing to store", stackId);
            return;
        }
        storeScript("push", STORE_TAG, stackId.toString(), "--force", "--cloud", cloudOf(stackId));
        logger.info("Stored the state and plan of stack {} as {}", stackId, STORE_TAG);
    }

    /**
     * Deletes the state and plan of the stack from the store, as {@code cdktf-store delete} does, after the stack was deleted.
     * It is only a warning if it fails, eg if it was never stored.
     */
    protected void deleteStateFromStore(StackId stackId) {
        storeScript("delete", STORE_TAG, stackId.toString(), "--cloud", cloudOf(stackId));
        logger.info("Deleted the state and plan of stack {} from the store", stackId);
    }

    private static String cloudOf(StackId stackId) {
        return stackId.cloud().toString().toLowerCase();
    }

    /**
     * Runs the script cdktf-store with the args, as a warning, not a failure, if it fails.
     */
    private void storeScript(String... args) {
        try {
            runStoreScript(args);
        } catch (RuntimeException e) {
            String message = String.format("Could not run cdktf-store %s: the stored state is out of date.  "
                                           + "Fix the cause, eg log in to the cloud, and run it by hand: afon-cli.sh cdktf-store %s",
                                           args[0], String.join(" ", args));
            logger.warn(message, e);
            System.err.println("WARNING: " + message);
        }
    }

    /**
     * Runs the script cdktf-store, which stops with an exception if it fails.
     */
    protected void runStoreScript(String... args) {
        new ScriptMain("cdktf/store.sh").run(args);
    }

    public int run(String command, Path path, String... commands) throws ManagedProcessException {
        return run(command, path, ProcessLogLevels.consoleLogsOutput() ? OutputStream.nullOutputStream() : System.out, commands);
    }

    public int run(String command, Path path, OutputStream stdOut, String... commands) throws ManagedProcessException {
        ManagedProcessBuilder pb = new ManagedProcessBuilder(command);
        for (String cmd : commands) {
            pb.addArgument(cmd);
        }
        if (path != null) {
            pb.setWorkingDirectory(path.toFile());
        }
        pb.setOutputStreamLogDispatcher(new ProcessLogLevels());
        pb.setDestroyOnShutdown(true)
          .addStdOut(stdOut)
          .addStdErr(ProcessLogLevels.consoleLogsOutput() ? OutputStream.nullOutputStream() : System.err)
          .setConsoleBufferMaxLines(7000);
        ManagedProcess p = pb.build();
        p.start();
        p.isAlive();
        p.waitForExit();
        logger.info("Command {} finished with value {}", command, p.exitValue());
        return p.exitValue();
    }

    @FunctionalInterface
    public interface PortForwardCall {
        /**
         * @param endpoint where the pod is reached, ie {@code localhost:<port>} of the port-forward
         */
        void run(String endpoint) throws Exception;
    }

    /**
     * Calls a pod through a port-forward, in the cluster of the current kubectl context, eg so that a service needs no access through
     * its ingress, which may be restricted. The port-forward is closed when the call returns.
     *
     * @param cloud      the cloud whose cluster the current kubectl context must be
     * @param properties the config, eg for the name of the cluster
     * @param podLabel   the label selector of the pod to forward to, eg {@code app=polaris}: the first running pod with it
     * @param podPort    the port that the pod listens on
     * @param call       what to run with the endpoint of the port-forward
     * @throws IllegalStateException if the current kubectl context is not the cluster of the cloud, or no pod has the label running
     */
    protected void withPortForward(AfonCloud cloud, AfonPropertyProvider properties, String podLabel, int podPort,
                                   PortForwardCall call) throws Exception {
        try (KubeClient client = new KubeClient(properties)) {
            client.requireContextOf(cloud);
            String pod = client.runningPodWithLabel(podLabel).orElseThrow(() -> new IllegalStateException(
                format("No running pod with the label %s in the kubectl context '%s'", podLabel, client.getContext())));
            try (PortForwarder forward = new PortForwarder(client, "default", pod, 0, podPort)) {
                call.run("localhost:" + forward.localPort());
            }
        }
    }

    /**
     * Restricts the Polaris ingress of the cluster of the current kubectl context, see {@link PolarisWhitelister}.
     */
    protected void whitelistPolaris(AfonCloud cloud, boolean withCaller, AfonPropertyProvider properties) throws Exception {
        try (KubeClient client = new KubeClient(properties)) {
            client.requireContextOf(cloud);
            new PolarisWhitelister(cloud, properties, client).set(withCaller, Optional.empty());
        }
        if (withCaller) {
            waitUntilPolarisAdmitsCaller(cloud, properties);
        }
    }

    private static final long ADMIT_TIMEOUT_MILLIS = 120_000;
    private static final long ADMIT_POLL_MILLIS    = 5_000;

    /**
     * Waits for the change of the ingress to take effect, which the load balancer takes some time to apply, until Polaris answers
     * this machine with something else than a 403, ie 200, or 401 to a call without authentication. Only warns if it still does not
     * after a while: what follows then fails with the 403.
     */
    private void waitUntilPolarisAdmitsCaller(AfonCloud cloud, AfonPropertyProvider properties) throws InterruptedException {
        String url = "https://" + properties.getCloudValue(cloud, AfonCloudProperty.POLARIS_DOMAIN_NAME) + "/api/catalog/v1/config";
        long deadline = System.currentTimeMillis() + ADMIT_TIMEOUT_MILLIS;
        String problem;
        while ((problem = problemAnswering(url, Set.of(200, 401))) != null) {
            if (System.currentTimeMillis() >= deadline) {
                logger.warn("{} {} after {}s: this machine may not have been admitted to Polaris yet",
                            url, problem, ADMIT_TIMEOUT_MILLIS / 1000);
                return;
            }
            logger.info("Waiting for Polaris to admit this machine: {} {}", url, problem);
            Thread.sleep(ADMIT_POLL_MILLIS);
        }
        logger.info("Polaris admits this machine: {} answers", url);
    }

    /**
     * @param polarisEndpoint the endpoint to call Polaris at, eg {@code localhost:54321} for a port-forward to its pod, or
     *                        {@code null} for its public domain name
     */
    public void createAllCatalogsFor(AfonCloud cloud, String polarisEndpoint) throws Exception {
        Properties overrides = polarisEndpointOverride(polarisEndpoint);
        try {
            executeMainOf(ManagePolarisCatalogsMain.class,
                    new String[]{RECREATE, cloud.toString()}, overrides);
        } catch (Exception e) {
            logger.error("Failed to delete and recreate all catalogs in Polaris for cloud `{}`: {}{}",
                         cloud, rootCauseOf(e), hintFor(e), e);
            throw e;
        }
    }

    private static Throwable rootCauseOf(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * @return what to try for the failure, if it is one that is known, else nothing
     */
    private static String hintFor(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("PKIX path building failed")) {
                return """

                       The certificate of Polaris is not trusted, which may be because it is not yet ready, if it is
                       reached through its domain name: if so, try re-running this step.""";
            }
        }
        return "";
    }

    /**
     * @param polarisEndpoint the endpoint to call Polaris at, eg {@code localhost:54321} for a port-forward to its pod, or
     *                        {@code null} for its public domain name
     */
    public void createCatalogFor(AfonCloud cloud, AfonCatalog catalog, String polarisEndpoint) throws Exception {
        executeMainOf(ManagePolarisCatalogsMain.class,
                new String[]{CREATE, cloud.toString(), catalog.toString()}, polarisEndpointOverride(polarisEndpoint));
    }

    /**
     * @param keycloakEndpoint the endpoint to call KeyCloak at, eg {@code localhost:54321} for a port-forward to its pod, or
     *                         {@code null} for its public domain name
     */
    public void configureOidcUsersFor(AfonCloud cloud, String keycloakEndpoint) throws Exception {
        executeMainOf(ConfigureUsersMain.class, keycloakEndpoint == null
            ? new String[]{cloud.toString()}
            : new String[]{cloud.toString(), ConfigureUsersMain.KEYCLOAK_ENDPOINT_ARG + keycloakEndpoint});
    }

    /**
     * Calls the KeyCloak of the cluster of the current kubectl context through a port-forward to its pod, as its ingress may not
     * admit this machine to its admin API, see {@link KeyCloakTunnel}.
     */
    protected void withKeyCloakTunnel(AfonCloud cloud, AfonPropertyProvider properties, PortForwardCall call) throws Exception {
        KeyCloakTunnel.run(cloud, properties, call::run);
    }

    /**
     * @param polarisEndpoint the endpoint to call Polaris at, eg {@code localhost:54321} for a port-forward to its pod, or
     *                        {@code null} for its public domain name
     */
    public void configurePolarisUsersFor(AfonCloud cloud, AfonCatalog catalog, String polarisEndpoint) throws Exception {
        executeMainOf(ConfigureUsersMain.class,
                new String[]{cloud.toString(), catalog.toString()}, polarisEndpointOverride(polarisEndpoint));
    }

    private static Properties polarisEndpointOverride(String polarisEndpoint) {
        Properties overrides = new Properties();
        if (polarisEndpoint != null) {
            overrides.setProperty(AfonProperty.POLARIS_ENDPOINT.getName(), polarisEndpoint);
        }
        return overrides;
    }

    /**
     * @param polarisEndpoint as for {@link #createCatalogFor}
     */
    public void deleteCatalogFor(AfonCloud cloud, AfonCatalog catalog, String polarisEndpoint) throws Exception {
        executeMainOf(ManagePolarisCatalogsMain.class,
                new String[]{DELETE, cloud.toString(), catalog.id()}, polarisEndpointOverride(polarisEndpoint));
    }

    public void deleteNamespaceFor(AfonCloud cloud, AfonCatalog catalog, String namespace) throws Exception {
        executeMainOf(
                ManagePolarisCatalogsMain.class,
                new String[]{DELETE, cloud.toString(), catalog.id(), namespace});
    }

    /**
     * @param polarisEndpoint as for {@link #createCatalogFor}
     */
    public void deleteTableFor(AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName, String polarisEndpoint)
    throws Exception {
        executeMainOf(
                ManagePolarisCatalogsMain.class,
                new String[]{DELETE, cloud.toString(), catalog.id(), namespace, tableName}, polarisEndpointOverride(polarisEndpoint));
    }

    /**
     * @param polarisEndpoint the endpoint to call Polaris at, eg {@code localhost:54321} for a port-forward to its pod, or
     *                        {@code null} for its public domain name
     */
    public void registerTable(AfonCloud cloud, AfonCatalog catalog, String namespace, String tableName, String metadata,
                              String polarisEndpoint) throws Exception {
        executeMainOf(RegisterPolarisTableMain.class,
                new String[]{cloud.toString(), catalog.toString(), namespace, tableName, metadata},
                polarisEndpointOverride(polarisEndpoint));
    }

    /**
     * @param polarisEndpoint the endpoint to call Polaris at, eg {@code localhost:54321} for a port-forward to its pod, or
     *                        {@code null} for its public domain name
     */
    public void runSpark(Class<? extends SparkRunner> sparkRunner, AfonCloud cloud, AfonCatalog catalog, String namespace,
                         String tableName, String polarisEndpoint) throws Exception {
        executeMainOf(sparkRunner,
                new String[]{cloud.toString(), catalog.toString(), namespace, tableName}, polarisEndpointOverride(polarisEndpoint));
    }

    public @NonNull String azureFirstFile(AfonPropertyProvider properties, String glob, boolean descendingOrder) {
        logger.info("Querying Azure for {}", glob);
        AzureReader reader = new AzureReader(properties);
        List<BlobItem> list = new ArrayList<>(reader.list(glob));
        Comparator<BlobItem> comparing = comparing((BlobItem o) -> o.getProperties().getCreationTime());
        if (descendingOrder) {
            comparing = comparing.reversed();
        }
        BlobItem latest = list
                .stream()
                .sorted(comparing)
                .findFirst()
                .get();
        logger.info("Latest blob was created at {}", latest.getProperties().getCreationTime());
        return format(
                "abfss://%s@%s.dfs.core.windows.net/%s",
                properties.getCatalogProperty("AZURE_BUCKET_NAME", "azure"),
                properties.getValue(AZURE_STORAGE_ACCOUNT),
                latest.getName());
    }


    public void runSnowflakeSql(AfonPropertyProvider properties, String[] statements) throws NoSuchAlgorithmException, InvalidKeySpecException, SQLException {
        SnowflakeConnectionFactory factory = new SnowflakeConnectionFactory(properties);
        try (Connection conn = factory.getConnection();
             Statement stmt = conn.createStatement()) {
            // Snowflake cancels a statement it cannot finish, eg as it cannot reach Polaris, rather than the driver waiting for it
            stmt.setQueryTimeout(SnowflakeConnectionFactory.QUERY_TIMEOUT_SECONDS);
            logger.info("Snowflake statements are cancelled after {}s", SnowflakeConnectionFactory.QUERY_TIMEOUT_SECONDS);
            for (String sql : statements) {
                logger.info("Running sql:\n{}", sql);
                ResultSet rs = stmt.executeQuery(sql);
                while (rs.next()) {
                    logger.info(rs.getString(1));
                }
            }
            conn.commit();
        }
    }

    /**
     * Checks that the CLI of the cloud is logged in, ie holds valid credentials, as the Polaris status scripts do.  It does not
     * check for permissions: a login without the rights needed still fails later.
     *
     * @throws IllegalStateException if not logged in, saying how to log in
     */
    protected void checkLogin(AfonCloud cloud) throws Exception {
        logger.info("Checking the login to {}", cloud);
        String[] check = loginCheck(cloud);
        int exit;
        try {
            exit = run(check[0], null, OutputStream.nullOutputStream(), Arrays.copyOfRange(check, 1, check.length));
        } catch (ManagedProcessException e) {
            exit = -1; // eg the CLI is not installed
            logger.warn("Cannot run {}: {}", String.join(" ", check), e.getMessage());
        }
        if (exit != 0) {
            throw new IllegalStateException(format("Not logged in to %s (%s failed): log in with %s, then rerun.",
                cloud.name(), String.join(" ", check), loginCommand(cloud)));
        }
    }

    /** @return the command that fails unless the CLI of the cloud is logged in */
    protected static String[] loginCheck(AfonCloud cloud) {
        return switch (cloud) {
            case AWS -> new String[]{"aws", "sts", "get-caller-identity"};
            case AZURE -> new String[]{"az", "account", "get-access-token", "--output", "none"};
            case GCP -> new String[]{"gcloud", "auth", "print-access-token"};
        };
    }

    /** @return the afon-cli.sh commands logging in to the cloud */
    protected static String loginCommand(AfonCloud cloud) {
        return switch (cloud) {
            case AWS -> "afon-cli.sh aws-login";
            case AZURE -> "afon-cli.sh azure-login (or azure-login-app)";
            case GCP -> "afon-cli.sh gcp-login (or gcp-login-as-service-account)";
        };
    }

    /**
     * Checks that the URL answers a GET with 200, eg that a server another stack deploys is up.
     * @param failure what to say if it doesn't, eg what to deploy first
     * @throws IllegalStateException if it doesn't answer, or not with 200
     */
    void checkAnswers(String url, String failure) throws InterruptedException {
        checkAnswers(url, failure, Set.of(200));
    }

    /**
     * Checks that the URL answers a GET with one of the given codes, eg that a server another stack deploys is up, which,
     * if it needs authentication, answers 401 to a call without.
     * @param failure what to say if it doesn't, eg what to deploy first
     * @throws IllegalStateException if it doesn't answer, or not with one of the codes
     */
    protected void checkAnswers(String url, String failure, Set<Integer> codes) throws InterruptedException {
        logger.info("Checking {} answers", url);
        String problem = problemAnswering(url, codes);
        if (problem == null) {
            logger.info("{} answers", url);
            return;
        }
        throw new IllegalStateException(format("%s %s. %s", url, problem, failure));
    }

    /**
     * Asks the URL with a GET, without logging.
     *
     * @return why it did not answer with one of the codes, eg "answered 503", or null if it did
     */
    private String problemAnswering(String url, Set<Integer> codes) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        try {
            HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return codes.contains(response.statusCode()) ? null : "answered " + response.statusCode();
        } catch (IOException e) {
            return "cannot be reached: " + e;
        }
    }

    public Step waitOnDNS(String domain, long timeout) {
        return new Step(
                stepNameWaitOn(domain),
                "wait on DNS for domain " + domain,
                () -> {
                    boolean resolved      = false;
                    boolean stopResolving = false;
                    long    start         = System.currentTimeMillis();
                    logger.info("Checking DNS for {}", domain);
                    while (!stopResolving) {
                        long elapsed = System.currentTimeMillis() - start;
                        logger.info("Waiting on DNS for domain {} (until timeout of {}): time elapsed {}", domain, formatDuration(timeout), formatDuration(elapsed));
                        if (!(elapsed < timeout)) break;
                        try {
                            InetAddress[] addresses = InetAddress.getAllByName(domain);
                            resolved = addresses != null && addresses.length > 0;
                            stopResolving = resolved;
                            if (!stopResolving) {
                                stopResolving = isWaitingInterrupted(timeout);
                            }
                        } catch (UnknownHostException e) {
                            stopResolving = isWaitingInterrupted(timeout);
                        }
                    }
                    if (!resolved) {
                        logger.warn("Timed out waiting on DNS for domain {} after {}", domain, formatDuration(timeout));
                    }
                });
    }


    /**
     * Waits until the URL answers a GET with 200, eg the discovery document of a KeyCloak that has just been deployed: its
     * DNS record resolving does not mean it serves, as its pod takes minutes to be ready, answered meanwhile with 502 or 503
     * by the ingress.  Gives up after the timeout, saying so, leaving whatever needs it to fail with its own error.
     *
     * @param pauseMs how long to wait between the requests
     */
    public Step waitOnAnswer(String url, long timeout, long pauseMs) {
        return new Step(
                stepNameWaitOnAnswer(url),
                "wait until " + url + " answers",
                () -> {
                    long start = System.currentTimeMillis();
                    long reported = -1; // when the last progress was logged: only the start, then each minute, not each request
                    logger.info("Waiting for {} to answer (until timeout of {})", url, formatDuration(timeout));
                    while (true) {
                        String problem = problemAnswering(url, Set.of(200));
                        long elapsed = System.currentTimeMillis() - start;
                        if (problem == null) {
                            logger.info("{} answers after {}", url, formatDuration(elapsed));
                            return;
                        }
                        if (reported < 0 || elapsed - reported >= PROGRESS_LOG_MILLIS) {
                            logger.info("Still waiting for it to answer after {}: it {}", formatDuration(elapsed), problem);
                            reported = elapsed;
                        }
                        if (elapsed + pauseMs >= timeout) {
                            logger.warn("Timed out waiting for {} to answer after {}: it {}", url, formatDuration(timeout), problem);
                            return;
                        }
                        try {
                            Thread.sleep(pauseMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                });
    }

    /** How often the wait for a URL to answer says it is still waiting. */
    private static final long PROGRESS_LOG_MILLIS = 60_000;

    protected @NonNull String stepNameWaitOnAnswer(String url) {
        return "wait_on_answer_of_" + url.replaceFirst("^https?://", "").replaceAll("[^A-Za-z0-9]+", "_");
    }

    protected @NonNull String stepNameWaitOn(String domain) {
        return "wait_on_" + (domain.isBlank() ? "domain" : domain.substring(0, domain.indexOf('.')));
    }

    private boolean isWaitingInterrupted(long timeout) {
        long pauseMs = 15_000;
        logger.debug("Waiting {}s until timeout of {}s", pauseMs / 1000, timeout / 1000);
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            return true;
        }
        return false;
    }

}
