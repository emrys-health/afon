package health.emrys.afon.ops.smoke;

import health.emrys.afon.ops.kube.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A server error from Gitea is retried, as it can answer one on the first requests after it starts, while any other answer is the call's.
 */
class GitSmokeMainRetryTest {

    private static final ExecResult ERROR_500 = new ExecResult(128, "", "fatal: unable to access 'http://gitea-http:3000/will/test-repo.git/': The requested URL returned error: 500\n", "git", "pod");
    private static final ExecResult NOT_FOUND = new ExecResult(128, "", "remote: Repository not found\nfatal: repository not found\n", "git", "pod");
    private static final ExecResult OK = new ExecResult(0, "", "", "git", "pod");

    private final List<Long> sleeps = new ArrayList<>();
    private int calls;

    private ExecResult run(int attempts, ExecResult... answers) throws Exception {
        Iterator<ExecResult> each = List.of(answers).iterator();
        calls = 0;
        return GitSmokeMain.retryingServerErrors(() -> {
            calls++;
            return each.next();
        }, attempts, 7, sleeps::add);
    }

    @Test
    void recognisesAServerErrorOfGiteaOnly() {
        assertThat(GitSmokeMain.isServerError(ERROR_500)).isTrue();
        assertThat(GitSmokeMain.isServerError(NOT_FOUND)).isFalse();
        assertThat(GitSmokeMain.isServerError(OK)).isFalse();
        assertThat(GitSmokeMain.isServerError(new ExecResult(128, "", "The requested URL returned error: 403", "git", "pod"))).isFalse();
    }

    @Test
    void triesAgainAfterAServerErrorAndKeepsTheAnswerThatFollows() throws Exception {
        assertThat(run(5, ERROR_500, ERROR_500, NOT_FOUND)).isSameAs(NOT_FOUND);
        assertThat(calls).isEqualTo(3);
        assertThat(sleeps).containsExactly(7L, 7L);
    }

    @Test
    void givesTheLastServerErrorWhenItKeepsComing() throws Exception {
        assertThat(run(3, ERROR_500, ERROR_500, ERROR_500, OK)).isSameAs(ERROR_500);
        assertThat(calls).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void doesNotTryAgainAnythingElse() throws Exception {
        assertThat(run(5, NOT_FOUND, OK)).isSameAs(NOT_FOUND);
        assertThat(calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(run(5, OK)).isSameAs(OK);
        assertThat(calls).isEqualTo(1);
    }
}
