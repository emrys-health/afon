package health.emrys.afon.cdk;

/**
 * Where Loki keeps its logs, in the object storage of the cloud, see {@link CloudPlatform#logStorage()}.
 *
 * @param serviceName     the object storage service, eg {@code S3}, as the Loki values name it for the reader
 * @param lokiType        the Loki object store type of its schema config, eg {@code s3}
 * @param storageYaml     the Loki {@code storage} config of that type, as YAML with no indentation, with the bucket, region
 *                        and credentials, so that it holds secrets
 * @param extraValuesYaml further values of the Loki chart, as YAML with no indentation, for what its {@code storage} has no
 *                        place for, eg the credentials of a store whose chart config takes none, so that it holds secrets; or blank
 */
public record LogStorage(String serviceName, String lokiType, String storageYaml, String extraValuesYaml) {

    public LogStorage(String serviceName, String lokiType, String storageYaml) {
        this(serviceName, lokiType, storageYaml, "");
    }
}
