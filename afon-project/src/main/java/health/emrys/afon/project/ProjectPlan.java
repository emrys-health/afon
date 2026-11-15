package health.emrys.afon.project;

import health.emrys.afon.config.StackId;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered stages that bring a project up: what outlives the TRE, the project's identity, its own Git if it has one, then its TRE.  Taking it
 * down is the reverse.  A stage is a stack, or an identity stage that has none.
 *
 * @param project the project
 * @param stages   the stages, in the order that brings the project up
 */
public record ProjectPlan(@NonNull AfonProject project, @NonNull List<Stage> stages) {

    /**
     * The name of a stage, which each plan holds once, as a {@link StackId} names a stack.
     */
    public enum StageId {
        /** Makes what outlives the nightly teardown: network, egress IP, audit and Git buckets. */
        CREATE,
        /** Makes the project's clients and group in the central identity, with {@code manage-identity}. */
        IDENTITY,
        /** Deploys the project's Git, which the nightly teardown leaves alone. */
        GIT,
        /** Deploys the project's TRE, which lives for a day. */
        TRE
    }

    /**
     * A stage of a plan.
     *
     * @param id       the stage's name
     * @param stack    the stack it deploys, or {@code null} for an identity stage
     * @param stateKey the key of the stack's state, {@code <project>/<stack-id>}, or {@code null} for an identity stage
     * @param nightly  whether the nightly flow deletes it and sets it up again
     */
    public record Stage(@NonNull StageId id, @Nullable StackId stack, @Nullable String stateKey, boolean nightly) {

        @Override
        public String toString() {
            return stack == null ? id.name().toLowerCase() : id.name().toLowerCase() + " (" + stack + ")";
        }
    }

    /**
     * @return the plan of the project
     */
    public static ProjectPlan of(AfonProject project) {
        List<Stage> stages = new ArrayList<>();
        stages.add(stage(project, StageId.CREATE, new StackId(project.cloud(), "create"), false));
        stages.add(new Stage(StageId.IDENTITY, null, null, false));
        if (project.git() != null) {
            stages.add(stage(project, StageId.GIT, new StackId(project.cloud(), "git"), false));
        }
        stages.add(stage(project, StageId.TRE, project.tre().stack(), project.tre().teardown() != null));
        return new ProjectPlan(project, List.copyOf(stages));
    }

    private static Stage stage(AfonProject project, StageId id, StackId stack, boolean nightly) {
        return new Stage(id, stack, project.id() + "/" + stack, nightly);
    }

    /**
     * @return the stages that the nightly flow runs: delete, then set up again, those that live for a day
     */
    public List<Stage> nightlyStages() {
        return stages.stream().filter(Stage::nightly).toList();
    }
}
