package health.emrys.afon.acceptance

import health.emrys.afon.config.deployment.{DeploymentConfigParser, WorkbenchSpec}
import org.scalatest.GivenWhenThen
import uk.co.odinconsultants.documentation_utils.SpecPretifier

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Path

/**
 * Every workbench type (Jupyter, VS Code, RStudio) is deployed behind the same
 * oauth2-proxy gate, so one config-driven spec covers all three: whatever
 * `workbench:` the deployment config declares, its domain must refuse
 * unauthenticated access and expose the proxy's health endpoint.
 *
 * The deployment config is `../docs/examples/emrys-demo.yaml` by default;
 * override with -Dafon.deployment.config=path/to/afon-deployment.yaml.
 */
class WorkbenchAuthGateSpec extends SpecPretifier with GivenWhenThen {

  private val configFile: Path = Path.of(
    sys.props.getOrElse("afon.deployment.config", "../docs/examples/emrys-demo.yaml"))

  private lazy val workbench: WorkbenchSpec =
    new DeploymentConfigParser().parse(configFile).workbench()

  private val http: HttpClient = HttpClient
    .newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  private def get(url: String): HttpResponse[String] =
    http.send(
      HttpRequest.newBuilder(URI.create(url)).GET().build(),
      HttpResponse.BodyHandlers.ofString())

  "The workbench" should {
    "refuse unauthenticated access" in {
      assume(workbench != null, s"no workbench declared in $configFile")
      val url = s"https://${workbench.domain()}/"
      Given(s"a ${workbench.`type`()} workbench deployed at $url behind oauth2-proxy")
      When(s"we GET $url without credentials")
      val response = get(url)
      Then(s"we are not let in (HTTP ${response.statusCode()})")
      assert(response.statusCode() != 200)
      assert(Set(302, 303, 401, 403).contains(response.statusCode()))
      response.headers().firstValue("Location").ifPresent { location =>
        And(s"we are sent to the sign-in flow at $location")
        assert(location.contains("/oauth2/"))
      }
    }

    "expose oauth2-proxy's health endpoint" in {
      assume(workbench != null, s"no workbench declared in $configFile")
      val url = s"https://${workbench.domain()}/ping"
      Given(s"oauth2-proxy fronts the ${workbench.`type`()} workbench with --ping-path=/ping")
      When(s"we GET $url")
      val response = get(url)
      Then(s"the proxy answers HTTP ${response.statusCode()} without requiring authentication")
      assert(response.statusCode() == 200)
    }
  }
}
