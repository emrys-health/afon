package health.emrys.afon.acceptance.gcp

import health.emrys.afon.acceptance.project.ProjectCloudSpec
import health.emrys.afon.acceptance.tags.{Gcp, Project, ProjectCloud}
import health.emrys.afon.config.AfonCloud

@Gcp
@Project
@ProjectCloud
class GcpProjectCloudSpec extends ProjectCloudSpec(AfonCloud.GCP)
