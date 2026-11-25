package health.emrys.afon.project;

import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered stages that bring a project stack up: what outlives its stacks, then, for a TRE project stack, its registration in the central identity, its
 * own Git if it has one, and its TRE; for an identity project stack, the central identity; for a data project stack, the Data Provider's Polaris.  Taking it
 * down is the reverse.  A stage is a stack, or an identity stage that has none.
 *
 * @param projectStack the project stack
 * @param stages   the stages, in the order that brings the project stack up
 */
public record ProjectStackPlan(@NonNull ProjectStack projectStack, @NonNull List<Stage> stages) {

    /**
     * The name of a stage, which each plan holds once, as a {@link StackId} names a stack.
     */
    public enum StageId {
        /** Makes what outlives the nightly teardown: network, egress IP, audit and Git buckets. */
        CREATE,
        /** Makes the project stack's clients and group in the central identity, with {@code manage-identity}. */
        REGISTER,
        /** Deploys the central identity, in the project stack that has that role, which the nightly teardown leaves alone. */
        IDENTITY,
        /** Deploys the project stack's Git, which the nightly teardown leaves alone. */
        GIT,
        /** Deploys the project stack's TRE, which lives for a day. */
        TRE,
        /** Deploys a Data Provider's Polaris and its catalogs, which the nightly teardown leaves alone. */
        DATA
    }

    /**
     * A stage of a plan.
     *
     * @param id       the stage's name
     * @param stack    the stack it deploys, or {@code null} for the registration, which has none
     * @param stateKey the key of the stack's state, {@code <project-stack>/<stack-id>}, or {@code null} for the registration
     * @param nightly  whether the nightly flow deletes it and sets it up again
     */
    public record Stage(@NonNull StageId id, @Nullable StackId stack, @Nullable String stateKey, boolean nightly) {

        @Override
        public String toString() {
            return stack == null ? id.name().toLowerCase() : id.name().toLowerCase() + " (" + stack + ")";
        }
    }

    /**
     * @return the plan of the project stack
     */
    public static ProjectStackPlan of(ProjectStack projectStack) {
        List<Stage> stages = new ArrayList<>();
        stages.add(stage(projectStack, StageId.CREATE, new StackId(projectStack.cloud(), "create"), false));
        if (projectStack instanceof TreProjectStack tre) {
            stages.add(new Stage(StageId.REGISTER, null, null, false));
            if (tre.git() != null) {
                stages.add(stage(projectStack, StageId.GIT, new StackId(projectStack.cloud(), "git"), false));
            }
            stages.add(stage(projectStack, StageId.TRE, projectStack.stack(), tre.tre().teardown() != null));
        } else if (projectStack instanceof DataProjectStack) {
            stages.add(stage(projectStack, StageId.DATA, projectStack.stack(), false));
        } else {
            stages.add(stage(projectStack, StageId.IDENTITY, projectStack.stack(), false));
        }
        return new ProjectStackPlan(projectStack, List.copyOf(stages));
    }

    private static Stage stage(ProjectStack projectStack, StageId id, StackId stack, boolean nightly) {
        return new Stage(id, stack, projectStack.id() + "/" + stack, nightly);
    }

    /**
     * @return the stages that the nightly flow runs: delete, then set up again, those that live for a day
     */
    public List<Stage> nightlyStages() {
        return stages.stream().filter(Stage::nightly).toList();
    }
}
