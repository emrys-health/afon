package health.emrys.afon.ops.project;

import health.emrys.afon.config.StackId;
import health.emrys.afon.project.AfonProject;
import health.emrys.afon.project.ProjectPlan;
import health.emrys.afon.project.ProjectPlan.Stage;
import health.emrys.afon.project.ProjectPlan.StageId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Afon commands that run each stage of a project's {@link ProjectPlan}, in the order that brings the project up, takes it down, or
 * rebuilds what lives for a day, with no more than what to run: running them is for {@link ManageProjectMain}.
 * <ul>
 *   <li>{@code create}: the script {@code <cloud>-create} for the TRE's stack, which makes what outlives the stacks;</li>
 *   <li>{@code identity}: {@code manage-identity create}, in the project's home like the rest, so that it uses the config that the project is
 *       made from, that of its env, and its Keycloak admin credentials, never those of whichever env is active;</li>
 *   <li>{@code git} and {@code tre}: {@code manage-stack setup} or {@code delete} of the stack.</li>
 * </ul>
 * Taking a project down deletes its stacks, and leaves what it made outside them, its buckets and its place in the central identity, which
 * are kept until the project is closed on purpose: {@code manage-identity close}.
 */
final class ProjectCommands {

    /**
     * A command to run for a stage.
     *
     * @param stage     the stage it is for
     * @param args      the args of the Afon command, {@code manage-stack setup gcp-full}, or none if the stage is skipped
     * @param inProject whether it runs in the home of the project, else in the deployer's own, which no stage does now
     * @param note      why the stage is skipped, if it is
     */
    record Command(StageId stage, List<String> args, boolean inProject, String note) {

        boolean skipped() {
            return args.isEmpty();
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
         * @return the commands that bring the project up
     */
    static List<Command> up(ProjectPlan plan, Stacks stacks) {
        List<Command> commands = new ArrayList<>();
        AfonProject project = plan.project();
        for (Stage stage : plan.stages()) {
            switch (stage.id()) {
                case CREATE -> commands.add(new Command(stage.id(), List.of(project.cloud() + "-create", project.tre().stack().toString()), true, null));
                case IDENTITY -> commands.add(new Command(stage.id(), List.of("manage-identity", "create", project.id().value()), true, null));
                case GIT, TRE -> commands.add(stackCommand(plan, stage, "setup", stacks));
            }
        }
        return commands;
    }

    /**
     * @return the commands that take the project down: its stacks are deleted, the last that was set up first
     */
    static List<Command> down(ProjectPlan plan, Stacks stacks) {
        List<Command> commands = new ArrayList<>();
        for (Stage stage : plan.stages()) {
            if (stage.id() == StageId.GIT || stage.id() == StageId.TRE) {
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
            StackId tre = plan.project().tre().stack();
            String note = stage.id() == StageId.GIT && stacks.declaresGit().test(tre)
                ? "Git is deployed with " + tre + ", as no stack " + stack + " is defined in the stacks file"
                : "no stack " + stack + " is defined in the stacks file";
            return new Command(stage.id(), List.of(), true, note);
        }
        return new Command(stage.id(), List.of("manage-stack", action, stack.toString()), true, null);
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
