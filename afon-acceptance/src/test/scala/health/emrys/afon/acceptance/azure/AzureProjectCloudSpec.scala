package health.emrys.afon.acceptance.azure

import health.emrys.afon.acceptance.project.ProjectCloudSpec
import health.emrys.afon.acceptance.tags.{Azure, Project, ProjectCloud}
import health.emrys.afon.config.AfonCloud

@Azure
@Project
@ProjectCloud
class AzureProjectCloudSpec extends ProjectCloudSpec(AfonCloud.AZURE)
