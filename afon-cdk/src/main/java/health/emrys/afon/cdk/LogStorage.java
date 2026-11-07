package health.emrys.afon.cdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Where Loki keeps its logs, in the object storage of the cloud, see {@link CloudPlatform#logStorage()}.
 *
 * @param serviceName     the object storage service, eg {@code S3}, as the Loki values name it for the reader
 * @param lokiType        the Loki object store type of its schema config, eg {@code s3}
 * @param storageYaml     the Loki {@code storage} config of that type, as YAML with no indentation, with the bucket and region,
 *                        and the credentials as {@code ${NAME}} references to {@code secrets}, never as values
 * @param extraValuesYaml further values of the Loki chart, as YAML with no indentation, for what its {@code storage} has no
 *                        place for, eg the credentials of a store whose chart config takes none, as {@code ${NAME}} references
 *                        to {@code secrets} too; or blank
 * @param secrets         the credentials as they are, by the name of the env var that Loki expands in its config, which are kept in a
 *                        Kubernetes secret, so that tofu prints them as sensitive, not in clear as it would in the Helm values, as
 *                        {@link #yamlString YAML strings}.  The references to them are not quoted, as the chart drops quotes.
 */
public record LogStorage(String serviceName, String lokiType, String storageYaml, String extraValuesYaml,
                         Map<String, String> secrets) {

    public LogStorage(String serviceName, String lokiType, String storageYaml, String extraValuesYaml) {
        this(serviceName, lokiType, storageYaml, extraValuesYaml, Map.of());
    }

    public LogStorage(String serviceName, String lokiType, String storageYaml) {
        this(serviceName, lokiType, storageYaml, "");
    }

    /**
     * Loki expands the references in its config as text, before it parses it as YAML, and the chart's templating drops any quotes
     * around a reference.  So that whatever a credential holds, eg the JSON of a service account, is a string after the expansion,
     * the secrets are kept as YAML double-quoted scalars: a JSON string is one.
     *
     * @return the value as a YAML double-quoted scalar, which is how it is kept in the secret
     */
    public static String yamlString(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot quote a credential", e);
        }
    }
}
