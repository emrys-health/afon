package health.emrys.afon.acceptance.project

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.tags.Project
import health.emrys.afon.cdk.DockerImages
import health.emrys.afon.config.AfonCloud
import health.emrys.afon.project.{FileProjectProvider, TreProject}
import health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM
import health.emrys.afon.security.keycloak.KeyCloakProvider
import org.scalatest.GivenWhenThen
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

import java.math.BigInteger
import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.util.Base64
import scala.jdk.CollectionConverters._

/**
 * The projects of the central identity, against a real KeyCloak, the one that Afon deploys, in a container: needs docker only.  No cloud is special,
 * so the specs of the clouds, which differ only in the names of the properties of the cloud, are those of the subclasses, one for each.
 *
 * Each project has a client for its TRE and one for its Git, and a group.  A token of a project's client names that project, and
 * only when the user is in its group, and carries the audiences of the project's Providers on the TRE client alone.  The tokens are
 * those that KeyCloak would issue, which its admin API generates for a user and a client without a browser login.
 */
abstract class AbstractProjectIdentitySpec(cloud: AfonCloud) extends SpecPretifier with GivenWhenThen {

  private val mapper = new ObjectMapper()
  private val http = HttpClient.newHttpClient()
  private val AdminUser = "admin"
  private val AdminPassword = "admin"

  private lazy val keycloak: GenericContainer[_] = {
    val container: GenericContainer[_] = new GenericContainer[Nothing](DockerImages.KEYCLOAK_IMAGE)
    container.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", AdminUser)
    container.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", AdminPassword)
    container.withEnv("KC_HTTP_ENABLED", "true")
    container.withExposedPorts(Integer.valueOf(8080))
    container.withCommand("start-dev")
    container.waitingFor(Wait.forHttp("/realms/master").forPort(8080).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
    container.start()
    container
  }

  private lazy val baseUrl = s"http://localhost:${keycloak.getMappedPort(8080)}"

  private lazy val provider: KeyCloakProvider = {
    val identity = new KeyCloakProvider(baseUrl, AdminUser, AdminPassword)
    identity.createRealm(identity.adminToken(), KEYCLOAK_REALM)
    identity
  }

  override def afterAll(): Unit = {
    try if (keycloak != null) keycloak.stop() finally super.afterAll()
  }

  /** A public key as a JWK, as the TRE operator gives it: the private key stays with them. */
  private def publicKeys(): java.util.Map[String, String] = {
    def jwk(kid: String): String = {
      val generator = KeyPairGenerator.getInstance("RSA")
      generator.initialize(2048)
      val key = generator.generateKeyPair().getPublic.asInstanceOf[RSAPublicKey]
      def b64(n: BigInteger) = Base64.getUrlEncoder.withoutPadding().encodeToString(n.toByteArray.dropWhile(_ == 0))
      s"""{"kty":"RSA","use":"sig","alg":"RS256","kid":"$kid","n":"${b64(key.getModulus)}","e":"${b64(key.getPublicExponent)}"}"""
    }
    Map("tre" -> jwk("tre"), "git" -> jwk("git")).asJava
  }

  private def projectOf(id: String, providers: Seq[String], ownGit: Boolean = true): TreProject = {
    val git = if (ownGit) s"\n         |  git: {domain: git.$id.example.org}" else ""
    val catalogs = providers.map(name =>
      s"  - {id: $name, catalogUri: 'https://polaris.$name.example/api', catalogName: $id, audience: provider-$name, bucket: $name-$id}").mkString("\n")
    FileProjectProvider.parse(
      s"""project: {id: $id, cloud: ${cloud.toString.toLowerCase}}
         |identity: {issuerUrl: 'https://id.example.org/realms/polaris', acr: mfa}
         |providers:
         |$catalogs
         |stacks:
         |  create: {auditBucket: $id-audit, gitBucket: $id-git}$git
         |  tre: {domains: {${cloud.name}_JUPYTER_DOMAIN_NAME: jupyter.$id.example.org}}
         |""".stripMargin).asInstanceOf[TreProject]
  }

  private def admin(): String = provider.adminToken()

  private def get(path: String): JsonNode = {
    val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + admin()).GET().build()
    val response = http.send(request, BodyHandlers.ofString())
    assert(response.statusCode() == 200, s"GET $path: HTTP ${response.statusCode()} ${response.body()}")
    mapper.readTree(response.body())
  }

  private def clientUuid(clientId: String): String =
    get(s"/admin/realms/$KEYCLOAK_REALM/clients?clientId=$clientId").get(0).get("id").asText()

  private def client(clientId: String): JsonNode = get(s"/admin/realms/$KEYCLOAK_REALM/clients/${clientUuid(clientId)}")

  /** The access token that KeyCloak issues the user for the client, as the admin API generates it. */
  private def tokenOf(clientId: String, userId: String): JsonNode =
    get(s"/admin/realms/$KEYCLOAK_REALM/clients/${clientUuid(clientId)}/evaluate-scopes/generate-example-access-token?userId=$userId")

  private def values(node: JsonNode): Seq[String] =
    if (node == null || node.isNull) Seq.empty
    else if (node.isArray) node.elements().asScala.map(_.asText()).toSeq
    else Seq(node.asText())

  private def groupMembers(groupName: String): Seq[String] = {
    val group = get(s"/admin/realms/$KEYCLOAK_REALM/groups?exact=true&search=$groupName").get(0).get("id").asText()
    get(s"/admin/realms/$KEYCLOAK_REALM/groups/$group/members").elements().asScala.map(_.get("id").asText()).toSeq
  }

  "A project in the central identity" should {

    "have a client for its TRE and one for its Git, which take no password and authenticate by key" in {
      Given("a project made in KeyCloak")
      val project = projectOf("cfg-2026-0001", Seq("nuh"))
      provider.ensureProject(project, publicKeys())

      Then("each client takes the browser flow only, with no direct grants, service accounts or full scope, and authenticates by a key")
      for (id <- Seq("cfg-2026-0001-tre", "cfg-2026-0001-git")) {
        val c = client(id)
        assert(c.get("standardFlowEnabled").asBoolean, id)
        assert(!c.get("directAccessGrantsEnabled").asBoolean, id)
        assert(!c.get("serviceAccountsEnabled").asBoolean, id)
        assert(!c.get("fullScopeAllowed").asBoolean, id)
        assert(c.get("clientAuthenticatorType").asText == "client-jwt", id)
        assert(c.get("attributes").get("use.jwks.string").asText == "true", id)
      }
      And("each has the exact redirect URIs of the project")
      assert(values(client("cfg-2026-0001-tre").get("redirectUris")).toSet ==
        Set("https://jupyter.cfg-2026-0001.example.org/oauth2/callback", "https://jupyter.cfg-2026-0001.example.org/hub/oauth_callback"))
      assert(values(client("cfg-2026-0001-git").get("redirectUris")) ==
        Seq("https://git.cfg-2026-0001.example.org/user/oauth2/keycloak/callback"))
    }

    "have only a TRE client when Git is deployed with the TRE" in {
      Given("a project with no Git of its own")
      val project = projectOf("cfg-2026-0009", Seq("nuh"), ownGit = false)
      provider.ensureProject(project, Map("tre" -> publicKeys().get("tre")).asJava)
      val ivy = provider.ensureUser("ivy@uni.example", "Ivy", "I")
      provider.addMember(project, ivy)

      Then("it has a TRE client, whose token names the project, and no Git client")
      assert(values(tokenOf("cfg-2026-0009-tre", ivy).get("project")) == Seq("cfg-2026-0009"))
      val found = get(s"/admin/realms/$KEYCLOAK_REALM/clients?clientId=cfg-2026-0009-git")
      assert(found.size() == 0)
    }

    "give a member a token naming the project, and a non-member none" in {
      Given("a project with a Provider, a member, and a user who is not a member")
      val project = projectOf("cfg-2026-0002", Seq("nuh"))
      provider.ensureProject(project, publicKeys())
      val alice = provider.ensureUser("alice@uni.example", "Alice", "A")
      val bob = provider.ensureUser("bob@uni.example", "Bob", "B")
      provider.addMember(project, alice)

      When("a token is issued to each for the project's TRE client")
      val aliceToken = tokenOf("cfg-2026-0002-tre", alice)
      val bobToken = tokenOf("cfg-2026-0002-tre", bob)

      Then("the member's token names the project, once, and the user by their immutable id, with the project's audiences")
      assert(values(aliceToken.get("project")) == Seq("cfg-2026-0002"))
      assert(aliceToken.get("polaris_principal").asText == s"afon:$alice")
      assert(values(aliceToken.get("aud")).toSet == Set("cfg-2026-0002-tre", "provider-nuh"))

      And("the non-member's token names no project")
      assert(values(bobToken.get("project")).isEmpty)

      And("the Git client's token carries no audience of a Provider, so that Git cannot replay it at one")
      assert(values(tokenOf("cfg-2026-0002-git", alice).get("aud")) == Seq("cfg-2026-0002-git"))
      assert(values(tokenOf("cfg-2026-0002-git", alice).get("project")) == Seq("cfg-2026-0002"))
    }

    "keep the projects of one user apart" in {
      Given("two projects, and a user who is a member of both, and another of the first only")
      val one = projectOf("cfg-2026-0003", Seq("nuh"))
      val two = projectOf("cfg-2026-0004", Seq("ucl"))
      provider.ensureProject(one, publicKeys())
      provider.ensureProject(two, publicKeys())
      val dana = provider.ensureUser("dana@uni.example", "Dana", "D")
      val erin = provider.ensureUser("erin@uni.example", "Erin", "E")
      provider.addMember(one, dana)
      provider.addMember(two, dana)
      provider.addMember(one, erin)

      Then("a token of each project's client names that project alone, with its own Provider's audience")
      val first = tokenOf("cfg-2026-0003-tre", dana)
      val second = tokenOf("cfg-2026-0004-tre", dana)
      assert(values(first.get("project")) == Seq("cfg-2026-0003"))
      assert(values(second.get("project")) == Seq("cfg-2026-0004"))
      assert(values(first.get("aud")).toSet == Set("cfg-2026-0003-tre", "provider-nuh"))
      assert(values(second.get("aud")).toSet == Set("cfg-2026-0004-tre", "provider-ucl"))

      And("the user of the first project only has no project in a token of the second")
      assert(values(tokenOf("cfg-2026-0003-tre", erin).get("project")) == Seq("cfg-2026-0003"))
      assert(values(tokenOf("cfg-2026-0004-tre", erin).get("project")).isEmpty)
    }

    "follow the Providers of the project when it is made again" in {
      Given("a project with one Provider, and a member")
      val before = projectOf("cfg-2026-0005", Seq("nuh"))
      provider.ensureProject(before, publicKeys())
      val frank = provider.ensureUser("frank@uni.example", "Frank", "F")
      provider.addMember(before, frank)
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-nuh"))

      When("it is made again with another Provider added")
      provider.ensureProject(projectOf("cfg-2026-0005", Seq("nuh", "ucl")), publicKeys())
      Then("its token carries the audience of both")
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-nuh", "provider-ucl"))

      When("it is made again with the first Provider gone")
      provider.ensureProject(projectOf("cfg-2026-0005", Seq("ucl")), publicKeys())
      Then("its token no longer carries the audience of the first, and the member is still a member")
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-ucl"))
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("project")) == Seq("cfg-2026-0005"))
    }

    "stop naming the project for a member who is removed, and issue no token once it is closed" in {
      Given("a project with a member")
      val project = projectOf("cfg-2026-0006", Seq("nuh"))
      provider.ensureProject(project, publicKeys())
      val gina = provider.ensureUser("gina@uni.example", "Gina", "G")
      provider.addMember(project, gina)
      assert(groupMembers("cfg-2026-0006").contains(gina))

      When("the member is removed")
      provider.removeMember(project, gina)
      Then("they are out of the group, and their token names no project")
      assert(!groupMembers("cfg-2026-0006").contains(gina))
      assert(values(tokenOf("cfg-2026-0006-tre", gina).get("project")).isEmpty)

      When("the project is closed")
      provider.closeProject(project)
      Then("its TRE client is disabled, and its Git client, which holds the code, is not")
      assert(!client("cfg-2026-0006-tre").get("enabled").asBoolean)
      assert(client("cfg-2026-0006-git").get("enabled").asBoolean)
    }

    "make a user once, with no password, to be invited" in {
      When("a user is made twice")
      val first = provider.ensureUser("hana@uni.example", "Hana", "H")
      val second = provider.ensureUser("hana@uni.example", "Hana", "H")
      Then("it is the same user, with the actions of an invitation and no credentials")
      assert(first == second)
      assert(provider.findUser("hana@uni.example").orElse("") == first)
      val user = get(s"/admin/realms/$KEYCLOAK_REALM/users/$first")
      assert(values(user.get("requiredActions")).toSet == Set("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP"))
      assert(get(s"/admin/realms/$KEYCLOAK_REALM/users/$first/credentials").size() == 0)
      assert(!provider.findUser("nobody@uni.example").isPresent)
    }
  }
}

/** The projects of the central identity, on AWS. */
@Project
class ProjectIdentityAwsSpec extends AbstractProjectIdentitySpec(AfonCloud.AWS)

/** The projects of the central identity, on Azure. */
@Project
class ProjectIdentityAzureSpec extends AbstractProjectIdentitySpec(AfonCloud.AZURE)

/** The projects of the central identity, on GCP. */
@Project
class ProjectIdentityGcpSpec extends AbstractProjectIdentitySpec(AfonCloud.GCP)
