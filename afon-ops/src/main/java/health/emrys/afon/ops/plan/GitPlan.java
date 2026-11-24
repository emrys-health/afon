package health.emrys.afon.ops.plan;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;

import java.util.List;

/**
 * The {@link ComponentPlan} for the {@code GitComponent}, which keeps the metadata of Gitea in a Postgres database,
 * {@code GITEA_DB_HOST}.  It holds nothing exclusively, and has nothing to wait for nor configure.
 */
public class GitPlan implements ComponentPlan {

    @Override
    public String label() {
        return "Gitea";
    }

    @Override
    public List<Database> databases(AfonCloud cloud, AfonPropertyProvider properties) {
        String host = properties.getValue(AfonProperty.GITEA_DB_HOST, "");
        return List.of(new Database(label(), AfonProperty.GITEA_DB_HOST.getName(), host));
    }
}
