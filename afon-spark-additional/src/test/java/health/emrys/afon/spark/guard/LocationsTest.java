package health.emrys.afon.spark.guard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocationsTest {

    @Test
    void theObjectStoresOfTheCloudsAreLetThrough() {
        for (String location : new String[]{"s3://bucket/key", "s3a://bucket/key", "S3A://bucket/key", "gs://bucket/key",
                                            "abfss://container@account.dfs.core.windows.net/key", "wasbs://container@account/key"}) {
            assertThat(Locations.refusal(location)).as(location).isEmpty();
        }
    }

    @Test
    void theLocalDiskAndEverythingElseIsRefused() {
        for (String location : new String[]{"file:///etc/hostname", "file:/etc/hostname", "local:///etc/hostname", "/etc/hostname",
                                            "relative/dir", "http://169.254.169.254/latest", "har:///tmp/x.har", "hdfs://nn/x", "", " ",
                                            null}) {
            assertThat(Locations.refusal(location)).as(String.valueOf(location)).hasValueSatisfying(why -> assertThat(why).contains("s3a"));
        }
    }

    @Test
    void theOptionsThatNameLocationsAreCheckedWhateverTheirCase() {
        assertThat(Locations.optionRefusal("path", "/etc/hostname")).isPresent();
        assertThat(Locations.optionRefusal("PATH", "/etc/hostname")).isPresent();
        assertThat(Locations.optionRefusal("checkpointLocation", "/tmp/checkpoint")).isPresent();
        assertThat(Locations.optionRefusal("path", "s3a://bucket/key")).isEmpty();
        assertThat(Locations.optionRefusal("header", "/etc/hostname")).as("not a location").isEmpty();
    }

    @Test
    void everyLocationOfPathsIsChecked() {
        assertThat(Locations.optionRefusal("paths", "[\"s3a://bucket/a\",\"gs://bucket/b\"]")).isEmpty();
        assertThat(Locations.optionRefusal("paths", "[\"s3a://bucket/a\",\"/etc/hostname\"]")).isPresent();
        assertThat(Locations.optionRefusal("paths", "[]")).isEmpty();
    }
}
