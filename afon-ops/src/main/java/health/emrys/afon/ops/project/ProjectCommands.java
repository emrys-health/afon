package health.emrys.afon.ops.project;

import health.emrys.afon.config.StackId;
import health.emrys.afon.project.AfonProject;
import health.emrys.afon.project.IdentityProject;
import health.emrys.afon.project.ProjectPlan;
import health.emrys.afon.project.ProjectPlan.Stage;
import health.emrys.afon.project.ProjectPlan.StageId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Afon commands that run each stage of a project's {@link ProjectPlan}, in the order that brings the project up, takes it down, or
 * rebuilds what lives for a day, with no more than what to run: running them is for {@link ManageProjectMain}.
 * <ul>
 *   <li>{@code create}: the script {@code <cloud>-create} for the TRE's stack, which makes what outlives the stacks, and, if the project names
 *       an egress IP, {@code db-whitelist}, which allows it on the database;</li>
 *   <li>{@code register}: {@code manage-identity create}, in the project's home like the rest, so that it uses the config that the project is
 *       made from, that of its env, and its Keycloak admin credentials, never those of whichever env is active.  If the project names the
 *       identity project, it reaches the admin API of that Keycloak through a port-forward to its pod, in the cluster of that project, so that
 *       no address is admitted to it;</li>
 *   <li>{@code identity}, {@code git} and {@code tre}: {@code manage-stack setup} or {@code delete} of the stack, the identity's being that of the
 *       project with the identity role.</li>
 * </ul>
 * Taking a project down deletes its stacks, and leaves what it made outside them, its buckets and its place in the central identity, which
 * are kept until the project is closed on purpose: {@code manage-identity close}.
 */
final class ProjectCommands {

    /**
     * A command of Afon, run in the home of a project, see {@link health.emrys.afon.project.ProjectHome}.
     *
     * @param project the id of the project whose home it runs in: the project that is run, or the identity project that it names
     * @param args    the args of the command, {@code manage-stack setup gcp-full}
     */
    record Invocation(String project, List<String> args) {
    }

    /**
     * What is run for a stage, which is its {@code run} commands in order, after the {@code before} ones that make it possible.
     *
     * @param stage  the stage it is for
     * @param before the commands that come before, which stop the stage at the first to fail
     * @param run    the commands of the stage, or none if it is skipped
     * @param note   why the stage is skipped, if it is
     */
    record Command(StageId stage, List<Invocation> before, List<Invocation> run, String note) {

        /** @return a stage of one command, in the home of the project */
        static Command of(StageId stage, String project, String... args) {
            return new Command(stage, List.of(), List.of(new Invocation(project, List.of(args))), null);
        }

        boolean skipped() {
            return run.isEmpty();
        }
    }

    /**
     * What the stacks file says of the stacks.
     *
     * @param defined    whether a stack is defined: a stage whose stack is not is skipped
     * @param declaresGit whether a stack deploys Git, which the stage of a project's own Git, if skipped, is then deployed with
     */
    record Stacks(Predicate<StackId> defined, Predicate<StackId> declaresGit) {
    }

    private ProjectCommands() {
    }

    /**
     * @param identityProject the identity project that the project names, or {@code null} if it names none: the registration then reaches the
     *                        admin API of the central identity at its public domain name, as it does not know whose cluster it is
     * @param identityKubeconfig the kubeconfig of the identity project's stack, which the registration reaches Keycloak through, or {@code null}
     *                        if there is no identity project
     * @return the commands that bring the project up
     */
    static List<Command> up(ProjectPlan plan, Stacks stacks, IdentityProject identityProject, Path identityKubeconfig) {
        List<Command> commands = new ArrayList<>();
        AfonProject project = plan.project();
        String id = project.id().value();
        String cloud = project.cloud().toString();
        for (Stage stage : plan.stages()) {
            switch (stage.id()) {
                case CREATE -> {
                    List<Invocation> run = new ArrayList<>(List.of(new Invocation(id, List.of(cloud + "-create", project.stack().toString()))));
                    if (project.egressIp() != null) {
                        // the address that the project names, which the database has not heard of, unlike the one it inherits from its env
                        run.add(new Invocation(id, List.of("db-whitelist", cloud)));
                    }
                    commands.add(new Command(stage.id(), List.of(), run, null));
                }
                case REGISTER -> commands.add(register(id, cloud, identityProject, identityKubeconfig));
                case IDENTITY, GIT -> commands.add(stackCommand(plan, stage, "setup", stacks));
                case TRE -> commands.add(withIdentityKubeconfig(stackCommand(plan, stage, "setup", stacks), cloud, identityProject));
            }
        }
        return commands;
    }

    /**
     * The registration in the central identity calls its admin API, which the ingress of its Keycloak may admit only the addresses of the
     * allowed CIDRs, which need not include this machine, so it reaches Keycloak through a port-forward to its pod, in the cluster of the
     * identity project, whose kubeconfig the stage before it writes, in the identity project's own home.  Nothing is admitted to, so there is
     * nothing to take off afterwards, even if the registration fails, and nothing is left running: the tunnel lives as long as the command.
     */
    private static Command register(String id, String cloud, IdentityProject identityProject, Path identityKubeconfig) {
        if (identityProject == null) {
            return Command.of(StageId.REGISTER, id, "manage-identity", "create", id);
        }
        String stack = identityProject.stack().toString();
        return new Command(StageId.REGISTER,
            List.of(new Invocation(identityProject.id().value(), List.of(cloud + "-update-kube-config", stack))),
            List.of(new Invocation(id, List.of("manage-identity", "create", id, "--kubeconfig", identityKubeconfig.toString()))),
            null);
    }

    /**
     * The TRE's stacks configure the Keycloak of the identity project, in its cluster, through a port-forward, for which the identity project's
     * kubeconfig is written first, in its own home, so that it is there, and of the cluster as it is now, when the stage is resumed.
     */
    private static Command withIdentityKubeconfig(Command command, String cloud, IdentityProject identityProject) {
        if (identityProject == null || command.skipped()) {
            return command;
        }
        return new Command(command.stage(),
                           List.of(new Invocation(identityProject.id().value(), List.of(cloud + "-update-kube-config", identityProject.stack().toString()))),
                           command.run(), command.note());
    }

    /**
     * @return the commands that take the project down: its stacks are deleted, the last that was set up first
     */
    static List<Command> down(ProjectPlan plan, Stacks stacks) {
        List<Command> commands = new ArrayList<>();
        for (Stage stage : plan.stages()) {
            if (stage.id() == StageId.IDENTITY || stage.id() == StageId.GIT || stage.id() == StageId.TRE) {
                commands.add(stackCommand(plan, stage, "delete", stacks));
            }
        }
        Collections.reverse(commands);
        return commands;
    }

    /**
     * @return the commands that rebuild what lives for a day: each such stack is deleted, then set up again
     */
    static List<Command> nightly(ProjectPlan plan, Stacks stacks) {
        List<Command> commands = new ArrayList<>();
        for (Stage stage : plan.nightlyStages()) {
            commands.add(stackCommand(plan, stage, "delete", stacks));
            commands.add(stackCommand(plan, stage, "setup", stacks));
        }
        return commands;
    }

    private static Command stackCommand(ProjectPlan plan, Stage stage, String action, Stacks stacks) {
        StackId stack = stage.stack();
        if (!stacks.defined().test(stack)) {
            StackId tre = plan.project().stack();
            String note = stage.id() == StageId.GIT && stacks.declaresGit().test(tre)
                ? "Git is deployed with " + tre + ", as no stack " + stack + " is defined in the stacks file"
                : "no stack " + stack + " is defined in the stacks file";
            return new Command(stage.id(), List.of(), List.of(), note);
        }
        return Command.of(stage.id(), plan.project().id().value(), "manage-stack", action, stack.toString());
    }

    /**
     * @return the commands from the first of the stage, or all of them if it is {@code null}
     * @throws IllegalArgumentException if no command is for the stage
     */
    static List<Command> from(List<Command> commands, StageId stage) {
        if (stage == null) {
            return commands;
        }
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i).stage() == stage) {
                return commands.subList(i, commands.size());
            }
        }
        throw new IllegalArgumentException("No stage " + stage.name().toLowerCase() + " to resume from in what is run: "
                                           + commands.stream().map(c -> c.stage().name().toLowerCase()).distinct().toList());
    }
}
