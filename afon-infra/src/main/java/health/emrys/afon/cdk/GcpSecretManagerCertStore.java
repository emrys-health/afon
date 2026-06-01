package health.emrys.afon.cdk;

import static java.lang.String.format;

/**
 * {@link TlsCertStore} backed by GCP Secret Manager. Secrets are project-scoped
 * and therefore persist independently of any single GKE cluster. The Secret
 * Manager API is enabled and the secret container is created on demand, both
 * idempotently.
 */
public class GcpSecretManagerCertStore implements TlsCertStore {

    private final String projectId;

    public GcpSecretManagerCertStore(String projectId) {
        this.projectId = projectId;
    }

    @Override
    public String uploadCommand(String key, String localFile) {
        return format("""
            gcloud services enable secretmanager.googleapis.com --project %1$s >/dev/null 2>&1 || true
            gcloud secrets describe %2$s --project %1$s >/dev/null 2>&1 || gcloud secrets create %2$s --replication-policy=automatic --project %1$s
            gcloud secrets versions add %2$s --data-file=%3$s --project %1$s""",
            projectId, key, localFile);
    }

    @Override
    public String downloadCommand(String key, String localFile) {
        return format(
            "gcloud secrets versions access latest --secret=%2$s --project %1$s > %3$s 2>/dev/null || rm -f %3$s",
            projectId, key, localFile);
    }
}
