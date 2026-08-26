package health.emrys.afon.polaris.catalogs;

import org.jspecify.annotations.NonNull;

public class GcpUtils {
    
    public static @NonNull String gsBucketUrl(String bucketName) {
        return "gs://" + bucketName;
    }
}
