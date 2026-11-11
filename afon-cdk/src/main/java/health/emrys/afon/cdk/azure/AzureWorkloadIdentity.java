package health.emrys.afon.cdk.azure;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.slf4j.Logger;

import static health.emrys.afon.config.AfonProperty.AZURE_APP_SECRET;
import static health.emrys.afon.config.AfonProperty.AZURE_WORKLOAD_IDENTITY;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * How the Azure app authenticates: with the secret of its app registration, {@link AfonProperty#AZURE_APP_SECRET}, or,
 * if {@link AfonProperty#AZURE_WORKLOAD_IDENTITY} is {@code true}, with a federated credential of the app registration
 * that trusts the OIDC issuer of the AKS cluster, so that there is no secret.  Then the Polaris pod runs under the service
 * account {@link #SERVICE_ACCOUNT}, which AKS workload identity exchanges for a token of the app.
 * <p>
 * The mode is only ever the explicit flag, never inferred from a blank secret, so that a secret left out by mistake fails
 * at once instead of switching the mode.
 * <p>
 * The federated credential is on the app registration, so is created by whoever owns it, with the issuer of the cluster,
 * the {@link #subject()} and the {@link #AUDIENCE}, see {@code scripts/azure/identity.sh}.
 */
public final class AzureWorkloadIdentity {

    private static final Logger logger = getLogger(AzureWorkloadIdentity.class);

    /** The namespace of the Polaris pod. */
    public static final String NAMESPACE       = "default";
    /** The service account of the Polaris pod, which the federated credential trusts. */
    public static final String SERVICE_ACCOUNT = "polaris";
    /** The audience of the tokens that AKS issues for Entra ID. */
    public static final String AUDIENCE        = "api://AzureADTokenExchange";

    private AzureWorkloadIdentity() {
    }

    /** The subject the federated credential must have, to trust the service account of the Polaris pod. */
    public static String subject() {
        return "system:serviceaccount:" + NAMESPACE + ":" + SERVICE_ACCOUNT;
    }

    /** Whether the app authenticates by workload identity.  {@code false} when the property is unset or blank. */
    public static boolean isEnabled(AfonPropertyProvider properties) {
        return Boolean.parseBoolean(properties.getValue(AZURE_WORKLOAD_IDENTITY, "false").trim());
    }

    /**
     * Checks the secret agrees with the mode.
     *
     * @throws IllegalArgumentException if the secret is needed and blank
     */
    public static void validate(AfonPropertyProvider properties) {
        boolean secretSet = !properties.getValue(AZURE_APP_SECRET, "").isBlank();
        if (isEnabled(properties)) {
            if (secretSet) {
                logger.warn("{}=true, so {} is ignored", AZURE_WORKLOAD_IDENTITY, AZURE_APP_SECRET);
            }
        } else if (!secretSet) {
            throw new IllegalArgumentException(format(
                "%s is not set: set it, or set %s=true if the app has a federated credential instead of a secret",
                AZURE_APP_SECRET, AZURE_WORKLOAD_IDENTITY));
        }
    }
}
