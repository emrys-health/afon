package health.emrys.afon.acceptance.azure

import health.emrys.afon.acceptance.project.AbstractProjectCloudSpec
import health.emrys.afon.acceptance.tags.{Azure, Project}
import health.emrys.afon.config.AfonCloud

@Azure
@Project
class AzureProjectCloudSpec extends AbstractProjectCloudSpec(AfonCloud.AZURE)
