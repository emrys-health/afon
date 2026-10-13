package health.emrys.afon.cdk;

import static java.lang.String.format;

/**
 * The TLS secret of a domain name, cached in the secret store of the cloud, eg Key Vault, so that it can be restored
 * rather than issued anew by cert-manager.  The cache is populated externally, ahead of deploy, by the cloud's
 * {@code scripts/<cloud>/issue-cert.sh}, issuer-agnostic from here, as it is just two named secrets.
 */
public final class CachedTlsSecret {

    /**
     * The label of the secrets that cert-manager manages, which is all it caches of them.  A restored secret without it is
     * not in the cache when the Certificate of the ingress is first checked, so cert-manager finds no secret and issues a
     * new certificate, using up the quota of Let's Encrypt, although it labels the secret straight after.
     */
    static final String CERT_MANAGER_SECRET_LABEL = "controller.cert-manager.io/fao";

    private CachedTlsSecret() {
    }

    /**
     * @return the names of the cached certificate and key secrets of the domain name, eg
     * {@code polaris-example-com-tls-crt} and {@code polaris-example-com-tls-key} for {@code polaris.example.com}
     */
    public static String[] secretNames(String domainName) {
        String kebabDomainName = domainName.replace(".", "-");
        return new String[]{kebabDomainName + "-tls-crt", kebabDomainName + "-tls-key"};
    }

    /**
     * Restores the TLS secret from the cloud's secret store before cert-manager's ingress-shim gets a chance to
     * request a fresh cert from Let's Encrypt.  A missing or empty entry is not an error, cert-manager simply issues
     * a new cert via its HTTP-01 ClusterIssuer as a fallback.
     *
     * @param fetchCertificate shell command printing the base64 certificate, or nothing if there is none
     * @param fetchKey         shell command printing the base64 key, or nothing if there is none
     * @param tlsSecretName    name of the Kubernetes TLS secret the ingress refers to
     * @return the shell script restoring the secret
     */
    public static String restoreCommand(String fetchCertificate, String fetchKey, String tlsSecretName) {
        return format(
            """
            CRT_B64=$(%1$s)
            KEY_B64=$(%2$s)
            if [ -n "$CRT_B64" ] && [ -n "$KEY_B64" ]; then
              kubectl create secret generic %3$s \
                --type=kubernetes.io/tls \
                --from-literal=tls.crt="$(echo "$CRT_B64" | base64 -d)" \
                --from-literal=tls.key="$(echo "$KEY_B64" | base64 -d)" \
                --dry-run=client -o yaml | kubectl apply --server-side --force-conflicts -f -
              kubectl annotate secret %3$s --overwrite \
                cert-manager.io/issuer-name=%4$s \
                cert-manager.io/issuer-kind=ClusterIssuer \
                cert-manager.io/issuer-group=cert-manager.io
              kubectl label secret %3$s --overwrite %5$s=true
            fi
            """, fetchCertificate, fetchKey, tlsSecretName, CdkHelper.CLUSTER_ISSUER_NAME, CERT_MANAGER_SECRET_LABEL);
    }
}
