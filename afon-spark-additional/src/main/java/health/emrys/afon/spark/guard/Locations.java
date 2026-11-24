package health.emrys.afon.spark.guard;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Where a query may read and write: the clouds' object stores alone, never the local disk of the server, which every session
 * shares.  A location with no scheme, or with {@code file}, is the local disk.
 */
final class Locations {

    /** The schemes of the object stores: AWS S3, Azure Blob Storage and Data Lake Storage, and Google Cloud Storage. */
    private static final Set<String> OBJECT_STORES = Set.of("s3", "s3a", "s3n", "abfs", "abfss", "wasb", "wasbs", "gs");

    /** The options that name a location, whichever the case they are given in. */
    private static final Set<String> LOCATION_OPTIONS = Set.of("path", "location", "checkpointlocation");

    private Locations() {
    }

    /**
     * @return why the location is refused, or empty if it is in an object store
     */
    static Optional<String> refusal(String location) {
        if (location == null || location.isBlank() || isObjectStore(location)) {
            return Optional.empty();
        }
        return Optional.of("the location " + location + " is refused: only the object stores of the clouds may be read or written, "
                           + "not the disk of the server");
    }

    /**
     * @return why the option is refused, or empty if it does not name a location, or names one in an object store
     */
    static Optional<String> optionRefusal(String key, String value) {
        if (key == null || !LOCATION_OPTIONS.contains(key.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return refusal(value);
    }

    private static boolean isObjectStore(String location) {
        try {
            String scheme = new URI(location.strip()).getScheme();
            return scheme != null && OBJECT_STORES.contains(scheme.toLowerCase(Locale.ROOT));
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }
}
