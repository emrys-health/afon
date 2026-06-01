package health.emrys.afon.cdk;

/**
 * Durable, cloud-specific store for the Let's Encrypt TLS cert so it survives a
 * cluster teardown and can be reused on the next build (rather than re-issued,
 * which risks ACME rate limits). Implementations return shell snippets run by
 * local-exec provisioners, operating on a local staging file because the cert
 * value is only known at runtime (cert-manager issues it) and so cannot be a
 * declarative Terraform resource.
 *
 * <p>The store must outlive the cluster, so implementations target storage that
 * is not part of the cluster's own lifecycle (a project-level GCP secret, a
 * pre-existing Azure Key Vault, etc.).
 */
public interface TlsCertStore {

    /**
     * Shell snippet that uploads {@code localFile} to durable storage under
     * {@code key}, creating the backing secret if it does not yet exist.
     */
    String uploadCommand(String key, String localFile);

    /**
     * Shell snippet that downloads {@code key} to {@code localFile}. If the key
     * does not exist it must leave {@code localFile} absent or empty (callers
     * treat a missing/empty file as "no stored cert").
     */
    String downloadCommand(String key, String localFile);
}
