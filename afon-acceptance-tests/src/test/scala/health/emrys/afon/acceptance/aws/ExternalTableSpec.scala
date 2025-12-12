package health.emrys.afon.acceptance.aws

import org.apache.polaris.core.admin.model.AuthenticationParameters.AuthenticationTypeEnum
import org.apache.polaris.core.admin.model.ConnectionConfigInfo.ConnectionTypeEnum
import org.apache.polaris.core.admin.model.StorageConfigInfo.StorageTypeEnum
import org.apache.polaris.core.admin.model.{AuthenticationParameters, Catalog, CatalogProperties, ConnectionConfigInfo, ExternalCatalog, GcpStorageConfigInfo, ServiceIdentityInfo, StorageConfigInfo}
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

import java.util
import java.util.List

class ExternalTableSpec extends SpecPretifier with GivenWhenThen {

  "An external table" should {
    "be visible by our Polaris" ignore {
      val uri: String = ???
      val authenticationParameters = new AuthenticationParameters(AuthenticationTypeEnum.BEARER)
      val serviceIdentity = new ServiceIdentityInfo(ServiceIdentityInfo.IdentityTypeEnum.AWS_IAM) // Huh? Only seems to be one kind
      val connectionConfig = new ConnectionConfigInfo(ConnectionTypeEnum.ICEBERG_REST,
        uri,
        authenticationParameters,
        serviceIdentity)

      val externalCatalogProperties = CatalogProperties
        .builder()
        .setDefaultBaseLocation("gs:/MY_DEFAULT")
        .build()

      val gcsServiceAccount: String = ???
      val storageType: StorageConfigInfo.StorageTypeEnum = ???
      val allowedLocations: util.List[String] = List.of()
      val externalStorageConfig = new GcpStorageConfigInfo(gcsServiceAccount,
        storageType,
        allowedLocations)


      val externalCatalog = ExternalCatalog
        .builder
        .setType(Catalog.TypeEnum.EXTERNAL)
        .setName("federatedCatalogName")
        .setConnectionConfigInfo(connectionConfig)
        .setProperties(externalCatalogProperties)
        .setStorageConfigInfo(externalStorageConfig)
        .build
    }
  }


}
