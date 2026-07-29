package health.emrys.afon.cluster;

import org.apache.hc.core5.http.HttpResponse;

public class ClusterUtils {

    public static boolean isFailure(HttpResponse response) {
        return response == null || response.getCode() / 100 != 2;
    }

}
