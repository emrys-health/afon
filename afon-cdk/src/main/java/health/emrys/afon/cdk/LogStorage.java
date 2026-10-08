package health.emrys.afon.cdk;

/**
 * Where Loki keeps its logs, in the object storage of the cloud, see {@link CloudPlatform#logStorage()}.
 *
 * @param serviceName the object storage service, eg {@code S3}, as the Loki values name it for the reader
 * @param lokiType    the Loki object store type of its schema config, eg {@code s3}
 * @param storageYaml the Loki {@code storage} config of that type, as YAML with no indentation, with the bucket, region
 *                    and credentials, so that it holds secrets
 */
public record LogStorage(String serviceName, String lokiType, String storageYaml) {
}
