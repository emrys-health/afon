package health.emrys.afon.acceptance.aws

import health.emrys.afon.config.AfonProperties.{AWS_S3_BUCKET_NAME, AWS_S3_EXTERNAL_BUCKET_NAME}
import health.emrys.afon.config.AfonPropertiesLoader
import health.emrys.afon.polaris.catalogs.{AwsCloudConfigForPolaris, JsonMaker}
import health.emrys.afon.polaris.comms.PolarisConfigurer
import org.apache.polaris.core.admin.model.{Catalog, CatalogProperties, CreateCatalogRequest}
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

class ExternalTableSpec extends SpecPretifier with GivenWhenThen {

  val client = AcceptanceTestConfig.instance.getPolarisClient
  val accessToken = AcceptanceTestConfig.instance.getAccessToken()

  "An external table" should {
    "be visible by our Polaris" in {
      val properties = new AfonPropertiesLoader().getProperties
      val externalBucket = AWS_S3_EXTERNAL_BUCKET_NAME.getValueFrom(properties)
      properties.setProperty(AWS_S3_BUCKET_NAME.getPropertyName, externalBucket)
      Given(f"a bucket called $externalBucket")


      val externalCatalogProperties = CatalogProperties
        .builder()
        .setDefaultBaseLocation(f"s3a://$externalBucket") // needs to be different to any other Polaris knows about
        .build()

      val externalStorageConfig = new AwsCloudConfigForPolaris(properties).getStorageConfigInfo()

      val jsonMaker = new JsonMaker()
      println(accessToken)

      val catalog = new Catalog(Catalog.TypeEnum.EXTERNAL,
        "federatedCatalogName",
        externalCatalogProperties,
        System.currentTimeMillis,
        System.currentTimeMillis,
        null,
        externalStorageConfig)

      val json = jsonMaker.toJson(new CreateCatalogRequest(catalog))

      When(f"Polaris is called with payload:\n$json")

      val response = client.postJsonCall(
        PolarisConfigurer.getCreateCatalogURI,
        json,
        accessToken)
      val responseCode = response.getCode
      println(response)
      Then("the response is HTTP 2XX")
      assert(responseCode / 100 == 2)
    }
  }


}
