package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonCloudProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.ops.kube.ExecResult;
import health.emrys.afon.ops.kube.KubeClient;
import health.emrys.afon.ops.project.SmokeMemberCredentials;
import health.emrys.afon.ops.smoke.config.SmokeConfig;
import health.emrys.afon.project.FileProjectStackProvider;
import health.emrys.afon.project.ProjectStackEnv;
import health.emrys.afon.security.keycloak.KeyCloakMemberLogin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static health.emrys.afon.config.AfonProperty.PROJECT_ID;
import static health.emrys.afon.config.AfonProperty.SMOKE_MEMBER;

/**
 * The notebook pod of the user that the smoke tests act as, which is made as the mode needs, whichever test wants it: the notebooks that are run in it, and the tests of what
 * is seen of a pod, such as Tetragon's and Promtail's.
 * <ul>
 *   <li>In project mode, the smoke member's: they are signed in to the TRE's JupyterHub as they would be from a browser, which starts their pod with their own token, and the
 *       server that they had, if any, is stopped first, so that the pod is new.  It needs their credentials, from {@code manage-identity smoke-credentials}.</li>
 *   <li>In non-project mode, that of {@code USER_ID}: JupyterHub is asked, through its API, to start it, or to say that it is running.</li>
 * </ul>
 * The {@link Hub} is the JupyterHub, as it makes the pod, and the {@link Pod} is what is done in it, which is what {@link PodNotebookRunner} and the tests of a pod work with.
 */
public final class UserPod {

    private UserPod() {
    }

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


    /**
     * @return the hub of the mode of the config, that makes the pod of the user of the smoke tests
     * @throws IOException if in project mode there is no smoke member, or they have no credentials, or the cloud has no Jupyter domain
     */
    public static Hub of(AfonPropertyProvider properties, AfonCloud cloud, JupyterHubSpawner spawner) throws IOException {
        return SmokeConfig.isProjectStack(properties) ? memberHub(properties, cloud, spawner) : adminHub(properties, spawner);
    }

    /**
     * Has the hub make the pod: stops the user's server, if it has one, signs them in, if that is how it is made, and waits for a pod that the sign-in made, running.
     *
     * @return the name of the pod
     */
    public static String newPod(Hub hub) throws Exception {
        hub.stopServer();
        Instant since = Instant.now();
        hub.signIn();
        return hub.awaitPod(since);
    }

    /**
     * @return the name of the pod of the user of the smoke tests, which the hub makes, for a test that needs a pod and does not care what runs in it
     */
    public static String podOf(KubeClient client, AfonPropertyProvider properties) throws Exception {
        AfonCloud cloud = new SmokeConfig(properties).cloud();
        return newPod(of(properties, cloud, new JupyterHubSpawner(client)));
    }

    /** The hub in project mode, that of the TRE, and the smoke member that is signed in to it, who gets a new pod with their token. */
    private static Hub memberHub(AfonPropertyProvider properties, AfonCloud cloud, JupyterHubSpawner spawner) throws IOException {
        String projectStack = properties.getValue(PROJECT_ID, "");
        String member = properties.getValue(SMOKE_MEMBER, "");
        if (projectStack.isBlank() || member.isBlank()) {
            throw new IOException("There is no smoke member: PROJECT_ID and SMOKE_MEMBER are set in the config of a project stack that lists smokeMembers, once configured");
        }
        Path secrets = FileProjectStackProvider.secretsDir(ProjectStackEnv.defaultAfonHome(), projectStack);
        SmokeMemberCredentials credentials = SmokeMemberCredentials.read(secrets, member).orElseThrow(() -> new IOException(
            "No credentials for " + member + " in " + secrets.resolve(SmokeMemberCredentials.FILE) + ": give them with manage-identity smoke-credentials"));
        String jupyterDomain = properties.getCloudValue(cloud, AfonCloudProperty.JUPYTER_DOMAIN_NAME, "");
        if (jupyterDomain.isBlank()) {
            throw new IOException(cloud.name() + "_JUPYTER_DOMAIN_NAME is not set, so there is no JupyterHub to sign in to");
        }
        URI realm = URI.create(properties.getOidcIssuerUrl(cloud));
        return new Hub() {
            @Override
            public void stopServer() throws Exception {
                spawner.stopServer(member, Duration.ofMinutes(3));
            }

            @Override
            public void signIn() throws Exception {
                KeyCloakMemberLogin.HubLanding landing = new KeyCloakMemberLogin(realm).signInToHub(URI.create("https://" + jupyterDomain + "/"), member,
                                                                                                      credentials.password(), credentials.totpSecret());
                System.out.printf("Signed %s in to https://%s/, which ended at %s (HTTP %d)%n", member, jupyterDomain, landing.at().getPath(), landing.status());
                if (landing.status() >= 400) {
                    throw new IOException("The hub refused " + member + " (HTTP " + landing.status() + " at " + landing.at().getPath() + "): " + landing.text()
                                          + ".  The hub admits the members that were listed when the TRE was deployed, so a smoke member added since is refused until the TRE"
                                          + " is deployed again: configure, then up <project-stack> --from tre");
                }
            }

            @Override
            public String awaitPod(Instant since) throws Exception {
                return spawner.awaitPod(member, since, Duration.ofMinutes(6));
            }
        };
    }

    /**
     * The hub in non-project mode, which is asked, through its API, to start the pod of {@code USER_ID}, or to say that it is running: there is no sign-in, so the
     * pod has no token of one, and the notebooks are given one.
     */
    private static Hub adminHub(AfonPropertyProvider properties, JupyterHubSpawner spawner) {
        return new Hub() {
            @Override
            public void stopServer() {
                // the pod is not stopped: the notebooks are given their token, so that of an earlier pod does not matter
            }

            @Override
            public void signIn() {
                // not signed in
            }

            @Override
            public String awaitPod(Instant since) throws Exception {
                return spawner.spawnWithJupyterToken(properties);
            }

            @Override
            public boolean podHasTokenOfSignIn() {
                return false;
            }
        };
    }
}
