package health.emrys.afon.acceptance

import health.emrys.afon.config.deployment.{CloudSpec, DeploymentConfigParser}
import org.scalatest.{Suite, Suites}

import java.nio.file.Path
import scala.jdk.CollectionConverters._

/**
 * Config-driven equivalent of running the per-cloud access specs by hand:
 * whichever clouds `afon-deployment.yaml` declares are the ones exercised,
 * reusing the existing [[AwsAccessSpec]]/[[AzureAccessSpec]]/[[GcpAccessSpec]]
 * verbatim — so the assertions cannot drift from the legacy path.
 *
 * The deployment config is `../docs/examples/emrys-demo.yaml` by default;
 * override with -Dafon.deployment.config=path/to/afon-deployment.yaml.
 */
class ConfigDrivenAccessSpec extends Suites(ConfigDrivenAccessSpec.declaredCloudSuites: _*)

object ConfigDrivenAccessSpec {

  private val configFile: Path = Path.of(
    sys.props.getOrElse("afon.deployment.config", "../docs/examples/emrys-demo.yaml"))

  def declaredCloudSuites: Seq[Suite] =
    new DeploymentConfigParser()
      .parse(configFile)
      .clouds()
      .asScala
      .toSeq
      .map(cloud =>
        cloud.provider() match {
          case CloudSpec.PROVIDER_AWS   => new AwsAccessSpec
          case CloudSpec.PROVIDER_AZURE => new AzureAccessSpec
          case CloudSpec.PROVIDER_GCP   => new GcpAccessSpec
          case other                    =>
            throw new IllegalArgumentException(
              s"No access spec for cloud provider '$other' (cloud '${cloud.id()}')")
        })
}
