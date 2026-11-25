package health.emrys.afon.ops.project;

import health.emrys.afon.config.StackId;
import health.emrys.afon.project.ProjectStack;
import health.emrys.afon.project.DnsDelegation;
import health.emrys.afon.project.IdentityProjectStack;
import health.emrys.afon.project.ProjectStackPlan;
import health.emrys.afon.project.ProjectStackPlan.Stage;
import health.emrys.afon.project.ProjectStackPlan.StageId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Afon commands that run each stage of a project stack's {@link ProjectStackPlan}, in the order that brings the project stack up, takes it down, or
 * rebuilds what lives for a day, with no more than what to run: running them is for {@link ManageProjectStackMain}.
 * <ul>
 *   <li>{@code create}: the script {@code <cloud>-create} for the TRE's stack, which makes what outlives the stacks, if the project stack names
 *       an egress IP, {@code db-whitelist}, which allows it on the database, and {@code dns-create-zones} for each stack that serves a domain, and, if the project stack says {@code dns.delegation: route53},
 *       {@code dns-delegate --route53}, which delegates its zone from the parent domain, and on AWS {@code aws-issue-cert --stack}, which issues the ACM
 *       certificates of its domains, as the stacks look them up;</li>
 *   <li>{@code register}: {@code manage-identity create}, in the project stack's home like the rest, so that it uses the config that the project stack is
 *       made from, that of its env, and its Keycloak admin credentials, never those of whichever env is active.  If the project stack names the
 *       identity project stack, it reaches the admin API of that Keycloak through a port-forward to its pod, in the cluster of that project stack, so that
 *       no address is admitted to it.  {@code manage-identity sync-members} follows, which makes the group of the project stack have the
 *       members that the file lists, and no others;</li>
 *   <li>{@code identity}, {@code git}, {@code tre} and {@code data}: {@code manage-stack setup} or {@code delete} of the stack, the identity's being
 *       that of the project stack with the identity role, and a Data Provider's its Polaris, which it sets up with its catalogs.</li>
 * </ul>
 * Taking a project stack down deletes its stacks, and leaves what it made outside them, its buckets and its place in the central identity, which
 * are kept until the project stack is closed on purpose: {@code manage-identity close}.
 */
final class ProjectStackCommands {

    /**
     * A command of Afon, run in the home of a project stack, see {@link health.emrys.afon.project.ProjectStackHome}.
     *
     * @param projectStack the id of the project stack whose home it runs in: the project stack that is run, or the identity project stack that it names
     * @param args    the args of the command, {@code manage-stack setup gcp-full}
     */
    record Invocation(String projectStack, List<String> args) {
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

        /** @return a stage of one command, in the home of the project stack */
        static Command of(StageId stage, String projectStack, String... args) {
            return new Command(stage, List.of(), List.of(new Invocation(projectStack, List.of(args))), null);
        }

        boolean skipped() {
            return run.isEmpty();
        }
    }

    /**
     * What the stacks file says of the stacks.
     *
     * @param defined    whether a stack is defined: a stage whose stack is not is skipped
     * @param declaresGit whether a stack deploys Git, which the stage of a project stack's own Git, if skipped, is then deployed with
     */
    record Stacks(Predicate<StackId> defined, Predicate<StackId> declaresGit) {
    }

    private ProjectStackCommands() {
    }

    /**
     * @param identityProjectStack the identity project stack that the project stack names, or {@code null} if it names none: the registration then reaches the
     *                        admin API of the central identity at its public domain name, as it does not know whose cluster it is
     * @param identityKubeconfig the kubeconfig of the identity project stack's stack, which the registration reaches Keycloak through, or {@code null}
     *                        if there is no identity project stack
     * @return the commands that bring the project stack up
     */
    static List<Command> up(ProjectStackPlan plan, Stacks stacks, IdentityProjectStack identityProjectStack, Path identityKubeconfig) {
        List<Command> commands = new ArrayList<>();
        ProjectStack projectStack = plan.projectStack();
        String id = projectStack.id().value();
        String cloud = projectStack.cloud().toString();
        for (Stage stage : plan.stages()) {
            switch (stage.id()) {
                case CREATE -> {
                    List<Invocation> run = new ArrayList<>(List.of(new Invocation(id, List.of(cloud + "-create", projectStack.stack().toString()))));
                    if (projectStack.egressIp() != null) {
                        // the address that the project stack names, which the database has not heard of, unlike the one it inherits from its env
                        run.add(new Invocation(id, List.of("db-whitelist", cloud)));
                    }
                    // the domains of the project stack's stacks are its own, so the zones that serve them are made, once <cloud>-create has made the resource
                    // group they are in; they are delegated from the parent domain by dns-delegate, as docs/dns_config.md says
                    for (Stage serving : plan.stages()) {
                        if (serving.stack() != null && List.of(StageId.IDENTITY, StageId.GIT, StageId.TRE, StageId.DATA).contains(serving.id())
                            && stacks.defined().test(serving.stack())) {
                            run.add(new Invocation(id, List.of("dns-create-zones", cloud, serving.stack().toString())));
                            if (projectStack.dnsDelegation() == DnsDelegation.ROUTE53) {
                                // so that the stage after can get its certificates: the zones are reached from the parent domain
                                run.add(new Invocation(id, List.of("dns-delegate", cloud, serving.stack().toString(), "--route53")));
                                if (cloud.equals("aws")) {
                                    // AWS stacks look up the ISSUED ACM certificate of each of their domains, which only exists once the zone is delegated, as ACM
                                    // validates it there: Azure and GCP stacks get theirs themselves if none is cached
                                    run.add(new Invocation(id, List.of("aws-issue-cert", "--stack", serving.stack().toString())));
                                }
                            }
                        }
                    }
                    commands.add(new Command(stage.id(), List.of(), run, null));
                }
                case REGISTER -> commands.add(register(id, cloud, identityProjectStack, identityKubeconfig));
                case IDENTITY, GIT -> commands.add(stackCommand(plan, stage, "setup", stacks));
                case TRE, DATA -> commands.add(withIdentityKubeconfig(stackCommand(plan, stage, "setup", stacks), cloud, identityProjectStack));
            }
        }
        return commands;
    }

    /**
     * The registration in the central identity calls its admin API, which the ingress of its Keycloak may admit only the addresses of the
     * allowed CIDRs, which need not include this machine, so it reaches Keycloak through a port-forward to its pod, in the cluster of the
     * identity project stack, whose kubeconfig the stage before it writes, in the identity project stack's own home.  Nothing is admitted to, so there is
     * nothing to take off afterwards, even if the registration fails, and nothing is left running: the tunnel lives as long as the command.
     */
    private static Command register(String id, String cloud, IdentityProjectStack identityProjectStack, Path identityKubeconfig) {
        if (identityProjectStack == null) {
            return new Command(StageId.REGISTER, List.of(), List.of(new Invocation(id, List.of("manage-identity", "create", id)),
                                                                   new Invocation(id, List.of("manage-identity", "sync-members", id))), null);
        }
        String stack = identityProjectStack.stack().toString();
        List<Invocation> run = List.of(new Invocation(id, List.of("manage-identity", "create", id, "--kubeconfig", identityKubeconfig.toString())),
                                       new Invocation(id, List.of("manage-identity", "sync-members", id, "--kubeconfig", identityKubeconfig.toString())));
        return new Command(StageId.REGISTER,
            List.of(new Invocation(identityProjectStack.id().value(), List.of(identityProjectStack.cloud().toString() + "-update-kube-config", stack))),
            run,
            null);
    }

    /**
     * The TRE's stacks configure the Keycloak of the identity project stack, in its cluster, through a port-forward, for which the identity project stack's
     * kubeconfig is written first, in its own home, so that it is there, and of the cluster as it is now, when the stage is resumed.
     */
    private static Command withIdentityKubeconfig(Command command, String cloud, IdentityProjectStack identityProjectStack) {
        if (identityProjectStack == null || command.skipped()) {
            return command;
        }
        return new Command(command.stage(),
                           List.of(new Invocation(identityProjectStack.id().value(), List.of(identityProjectStack.cloud().toString() + "-update-kube-config",
                                                                        identityProjectStack.stack().toString()))),
                           command.run(), command.note());
    }

    /**
     * @return the commands that take the project stack down: its stacks are deleted, the last that was set up first
     */
    static List<Command> down(ProjectStackPlan plan, Stacks stacks) {
        List<Command> commands = new ArrayList<>();
        for (Stage stage : plan.stages()) {
            if (stage.id() == StageId.IDENTITY || stage.id() == StageId.GIT || stage.id() == StageId.TRE || stage.id() == StageId.DATA) {
                commands.add(stackCommand(plan, stage, "delete", stacks));
            }
        }
        Collections.reverse(commands);
        return commands;
    }

    /**
     * @return the commands that rebuild what lives for a day: each such stack is deleted, then set up again
     */
    static List<Command> nightly(ProjectStackPlan plan, Stacks stacks) {
        List<Command> commands = new ArrayList<>();
        for (Stage stage : plan.nightlyStages()) {
            commands.add(stackCommand(plan, stage, "delete", stacks));
            commands.add(stackCommand(plan, stage, "setup", stacks));
        }
        return commands;
    }

    private static Command stackCommand(ProjectStackPlan plan, Stage stage, String action, Stacks stacks) {
        StackId stack = stage.stack();
        if (!stacks.defined().test(stack)) {
            StackId tre = plan.projectStack().stack();
            String note = stage.id() == StageId.GIT && stacks.declaresGit().test(tre)
                ? "Git is deployed with " + tre + ", as no stack " + stack + " is defined in the stacks file"
                : "no stack " + stack + " is defined in the stacks file";
            return new Command(stage.id(), List.of(), List.of(), note);
        }
        return Command.of(stage.id(), plan.projectStack().id().value(), "manage-stack", action, stack.toString());
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

    private static final java.util.regex.Pattern STEP = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    /**
     * Has the first command resume its stack command from the step, as {@code manage-stack setup <stack> <step>} does: the steps of the stack before it are
     * not run, so that a stage that failed partway, eg after the deploy, is resumed at the step that failed, and what the steps before it did is not done again.
     *
     * @param commands the commands, the first of which is of the stage to resume
     * @return the commands, the first with the step added to its command on the stack
     * @throws IllegalArgumentException if the step is not a name, or the first command is not of a stack, so has no steps of its own to resume from
     */
    static List<Command> resumeAt(List<Command> commands, String step) {
        if (step == null || !STEP.matcher(step).matches()) {
            throw new IllegalArgumentException("'" + step + "' is not the name of a step: letters, digits, '_', '.' and '-'");
        }
        Command first = commands.isEmpty() ? null : commands.get(0);
        int index = -1;
        if (first != null && !first.skipped()) {
            for (int i = first.run().size() - 1; i >= 0; i--) {
                List<String> args = first.run().get(i).args();
                if (args.size() == 3 && args.get(0).equals("manage-stack")) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("Stage " + (first == null ? "" : first.stage().name().toLowerCase() + " ") + "has no steps to resume from: only a stage "
                                               + "that sets up or deletes a stack has, eg " + List.of(StageId.IDENTITY, StageId.GIT, StageId.TRE, StageId.DATA).stream()
                                                                                                        .map(id -> id.name().toLowerCase()).toList());
        }
        List<Invocation> run = new ArrayList<>(first.run());
        Invocation stackCommand = run.get(index);
        List<String> args = new ArrayList<>(stackCommand.args());
        args.add(step);
        run.set(index, new Invocation(stackCommand.projectStack(), List.copyOf(args)));
        List<Command> resumed = new ArrayList<>(commands);
        resumed.set(0, new Command(first.stage(), first.before(), run, first.note()));
        return resumed;
    }

    /**
     * @return the commands up to and including those of the stage, or all of them if it is {@code null}
     * @throws IllegalArgumentException if no command is for the stage
     */
    static List<Command> until(List<Command> commands, StageId stage) {
        if (stage == null) {
            return commands;
        }
        for (int i = commands.size() - 1; i >= 0; i--) {
            if (commands.get(i).stage() == stage) {
                return commands.subList(0, i + 1);
            }
        }
        throw new IllegalArgumentException("No stage " + stage.name().toLowerCase() + " to stop after in what is run: "
                                           + commands.stream().map(c -> c.stage().name().toLowerCase()).distinct().toList());
    }
}
