package health.emrys.afon.acceptance.aws

import health.emrys.afon.config.AfonProperties.AWS_REGION
import health.emrys.afon.config.{AfonProperties, AfonPropertiesLoader}
import health.emrys.afon.polaris.catalogs.{AwsCloudConfigForPolaris, JsonMaker}
import health.emrys.afon.polaris.comms.PolarisConfigurer
import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum
import org.apache.polaris.core.admin.model.{AuthenticationParameters, AwsStorageConfigInfo, Catalog, CatalogProperties, ConnectionConfigInfo, CreateCatalogRequest, ExternalCatalog, GcpStorageConfigInfo, ServiceIdentityInfo, StorageConfigInfo}
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

import java.util
import java.util.List

class ExternalTableSpec extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  "An external table" should {
    "be visible by our Polaris" ignore {
      val properties = new AfonPropertiesLoader().getProperties
      val uri: String = String.format("https://glue.%s.amazonaws.com", AWS_REGION.getValueFrom(properties))
      val authenticationParameters = new AuthenticationParameters(AuthenticationTypeEnum.BEARER)
      val serviceIdentity = new ServiceIdentityInfo(ServiceIdentityInfo.IdentityTypeEnum.AWS_IAM) // Huh? Only seems to be one kind
      val connectionConfig = new ConnectionConfigInfo(ConnectionTypeEnum.ICEBERG_REST,
        uri,
        authenticationParameters,
        serviceIdentity)

      val externalCatalogProperties = CatalogProperties
        .builder()
        .setDefaultBaseLocation("s3://emrys-afon-bucket/acceptancetests/smoketesttable251106221055/")
        .build()

//      val gcsServiceAccount: String = ???
//      val allowedLocations: util.List[String] = List.of()
//      val gcpExternalStorageConfig = new GcpStorageConfigInfo(gcsServiceAccount,
//        StorageTypeEnum.GCS,
//        allowedLocations)

      val externalStorageConfig = new AwsCloudConfigForPolaris(properties).getStorageConfigInfo()

      val externalCatalog = ExternalCatalog
        .builder
        .setType(Catalog.TypeEnum.EXTERNAL)
        .setName("federatedCatalogName")
        .setConnectionConfigInfo(connectionConfig)
        .setCreateTimestamp(System.currentTimeMillis())
        .setLastUpdateTimestamp(System.currentTimeMillis())
        .setProperties(externalCatalogProperties)
        .setStorageConfigInfo(externalStorageConfig)
        .build

      val jsonMaker = new JsonMaker()
      println(accessToken)

      val catalog = new Catalog(Catalog.TypeEnum.EXTERNAL,
        "federatedCatalogName",
        externalCatalogProperties,
        System.currentTimeMillis,
        System.currentTimeMillis,
        null,
        externalStorageConfig)

      val response = client.postJsonCall(PolarisConfigurer.getCreateCatalogURI,
        jsonMaker.toJson(new CreateCatalogRequest(catalog)),
//        jsonMaker.toJson(new CreateCatalogRequest(externalCatalog)),
//        new AwsCloudConfigForPolaris(properties).catalogConfig(),
        accessToken)
      val responseCode = response.getCode
      println(response)
      assert(responseCode / 100 == 2)
    }
  }


}
