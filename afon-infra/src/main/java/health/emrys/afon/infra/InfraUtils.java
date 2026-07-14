package health.emrys.afon.infra;

import org.apache.hc.core5.http.HttpResponse;

public class InfraUtils {

    public static boolean isFailure(HttpResponse response) {
        return response == null || response.getCode() / 100 != 2;
    }

}
