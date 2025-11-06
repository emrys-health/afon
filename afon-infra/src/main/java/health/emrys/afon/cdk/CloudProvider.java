package health.emrys.afon.cdk;

import com.hashicorp.cdktf.providers.null_provider.resource.Resource;

import java.util.List;

public interface CloudProvider {

    List<Resource> getDependables();

    String getBase64CertData();

    String getEndpoint();

    String getToken();

}
