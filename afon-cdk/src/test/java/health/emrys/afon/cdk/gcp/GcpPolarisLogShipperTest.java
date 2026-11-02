package health.emrys.afon.cdk.gcp;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polaris' logs go to GCS under {@code polaris/<cluster>/}, through its S3 compatible API, with the HMAC key from a secret.
 */
class GcpPolarisLogShipperTest {

    private final GcpPolarisLogShipper shipper = new GcpPolarisLogShipper("full-emrys", "polaris-logs", "europe-west2", "GOOG1EX", "s3cret");

    @Test
    void writesToTheBucketUnderThePrefixOfTheCluster() {
        assertThat(shipper.output())
            .contains("Name s3", "Match polaris.*", "bucket polaris-logs", "region europe-west2",
                      "endpoint https://storage.googleapis.com", "use_put_object On")
            .contains("s3_key_format /polaris/full-emrys/%Y/%m/%d/%H%M%S-$UUID.json.gz");
    }

    @Test
    void theKeyIsReadFromTheSecretIntoTheEnvVarsThatFluentBitReadsItsS3CredentialsFrom() {
        String values = shipper.values();

        assertThat(values).contains("name: AWS_ACCESS_KEY_ID", "name: AWS_SECRET_ACCESS_KEY", "name: fluent-bit-polaris-gcs")
                          .doesNotContain("GOOG1EX", "s3cret");
    }

    @Test
    void theValuesAreYaml() {
        Map<String, Object> values = new Yaml().load(shipper.values());

        assertThat(values).containsKeys("serviceAccount", "env", "config");
    }
}
