package health.emrys.afon.ops.kube.tools;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserPodTest {

    @Test
    void aNewPodIsMadeByStoppingTheServerSigningInAndWaitingForAPodThatTheSignInMade() throws Exception {
        List<String> calls = new ArrayList<>();
        Instant[] since = new Instant[1];
        Instant before = Instant.now();

        String pod = UserPod.newPod(new UserPod.Hub() {
            @Override
            public void stopServer() {
                calls.add("stop");
            }

            @Override
            public void signIn() {
                calls.add("sign in");
            }

            @Override
            public String awaitPod(Instant asked) {
                calls.add("await");
                since[0] = asked;
                return "jupyter-smoke-40example-2eorg";
            }
        });

        assertThat(pod).isEqualTo("jupyter-smoke-40example-2eorg");
        assertThat(calls).containsExactly("stop", "sign in", "await");
        // a pod made since before the sign-in, not one that was running already
        assertThat(since[0]).isAfterOrEqualTo(before);
    }
}
