package health.emrys.afon.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The cloud-agnostic names of the properties that each cloud has of its own, by the convention {@code <CLOUD>_<NAME>}, eg
 * {@link #POLARIS_DOMAIN_NAME} is {@code AWS_POLARIS_DOMAIN_NAME} on AWS, so that what is deployed to any cloud takes
 * the config of that cloud.
 *
 * Only the properties that can logically be present in different clouds are listed, not those that only one cloud can
 * have, eg the AWS certificate ARNs and Route 53 zones, which are {@link AfonProperty}s of that cloud.  A cloud need not
 * have every one, eg GCP may not have log bucket, see {@link #forCloud(AfonCloud)}.
 */
public enum AfonCloudProperty {
    POLARIS_DOMAIN_NAME,
    JUPYTER_DOMAIN_NAME,
    OIDC_DOMAIN_NAME,
    POLARIS_ALLOWED_CIDRS,
    EGRESS_IP,
    POLARIS_LOG_BUCKET,
    KEYCLOAK_EXTERNAL,
    GITEA_BUCKET;

    /**
     * @return the property of the cloud, or empty if the cloud has none of this name
     */
    public Optional<AfonProperty> forCloud(AfonCloud cloud) {
        String name = cloud.toString().toUpperCase(Locale.ROOT) + "_" + name();
        return Arrays.stream(AfonProperty.values()).filter(property -> property.getName().equals(name)).findFirst();
    }
}
