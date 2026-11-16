package health.emrys.afon.acceptance.aws

import health.emrys.afon.acceptance.project.AbstractProjectCloudSpec
import health.emrys.afon.acceptance.tags.{Aws, Project}
import health.emrys.afon.config.AfonCloud

@Aws
@Project
class AwsProjectCloudSpec extends AbstractProjectCloudSpec(AfonCloud.AWS)
