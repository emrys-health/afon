package health.emrys.afon.acceptance.gcp

import health.emrys.afon.acceptance.project.AbstractProjectCloudSpec
import health.emrys.afon.acceptance.tags.{Gcp, Project}
import health.emrys.afon.config.AfonCloud

@Gcp
@Project
class GcpProjectCloudSpec extends AbstractProjectCloudSpec(AfonCloud.GCP)
