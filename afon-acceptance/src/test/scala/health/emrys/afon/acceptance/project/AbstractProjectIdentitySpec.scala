package health.emrys.afon.acceptance.project

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.tags.{Project, ProjectIdentity}
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
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, KeyPairGenerator, PrivateKey, Signature}
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
         |members: [ada@example.org]
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

  private def adminPut(path: String, json: String): Unit = {
    val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + admin())
      .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)).build()
    val response = http.send(request, BodyHandlers.ofString())
    assert(response.statusCode() / 100 == 2, s"PUT $path: HTTP ${response.statusCode()} ${response.body()}")
  }

  /** Runs a script of the repository, with the Afon home given, and says what it printed. */
  private def runScript(script: String, afonHome: Path, args: String*): String = {
    val path = Path.of("..", "scripts").resolve(script).toAbsolutePath.normalize().toString
    val builder = new ProcessBuilder((Seq("bash", path) ++ args).asJava).redirectErrorStream(true)
    builder.environment().put("AFON_HOME", afonHome.toString)
    val process = builder.start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    assert(process.waitFor() == 0, s"$script ${args.mkString(" ")}: $output")
    output
  }

  /** The private key of a PEM, whichever form openssl made it in, which openssl itself turns into the PKCS#8 that Java reads. */
  private def privateKeyOf(pem: Path): PrivateKey = {
    val process = new ProcessBuilder("openssl", "pkcs8", "-topk8", "-nocrypt", "-in", pem.toString, "-outform", "DER").start()
    val der = process.getInputStream.readAllBytes()
    assert(process.waitFor() == 0, s"openssl could not read $pem")
    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der))
  }

  /** The assertion by which a client authenticates to the token endpoint, a JWT signed with its private key. */
  private def clientAssertion(clientId: String, key: PrivateKey): String = {
    def b64(bytes: Array[Byte]) = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    val now = System.currentTimeMillis() / 1000
    val header = b64(s"""{"alg":"RS256","typ":"JWT","kid":"$clientId"}""".getBytes(StandardCharsets.UTF_8))
    val payload = b64(s"""{"iss":"$clientId","sub":"$clientId","aud":"$baseUrl/realms/$KEYCLOAK_REALM","jti":"${java.util.UUID.randomUUID()}","iat":$now,"exp":${now + 60}}"""
                        .getBytes(StandardCharsets.UTF_8))
    val signature = Signature.getInstance("SHA256withRSA")
    signature.initSign(key)
    signature.update(s"$header.$payload".getBytes(StandardCharsets.UTF_8))
    s"$header.$payload.${b64(signature.sign())}"
  }

  /**
   * What the token endpoint answers a client that presents a code that was never issued, with the assertion: KeyCloak authenticates the client before it
   * looks at the code, so that an answer about the code means that the client was authenticated, and one about the client that it was not.
   */
  private def tokenEndpointAnswer(clientId: String, assertion: String, redirectUri: String): (Int, String) = {
    def enc(value: String) = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
    val form = Seq("grant_type" -> "authorization_code", "code" -> "never-issued", "redirect_uri" -> redirectUri, "client_id" -> clientId,
                   "client_assertion_type" -> "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", "client_assertion" -> assertion)
      .map { case (k, v) => s"${enc(k)}=${enc(v)}" }.mkString("&")
    val request = HttpRequest.newBuilder(URI.create(s"$baseUrl/realms/$KEYCLOAK_REALM/protocol/openid-connect/token"))
      .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()
    val response = http.send(request, BodyHandlers.ofString())
    (response.statusCode(), mapper.readTree(response.body()).path("error").asText())
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

  "A TRE project registered in the central identity provider" should {

    "have a TRE client and a Git client in the identity provider, which take no password and authenticate by key" in {
      Given("a TRE project with a Git of its own, registered in the identity provider")
      val project = projectOf("cfg-2026-0001", Seq("data1"))
      provider.ensureProject(project, publicKeys())

      Then("each of its two clients in the identity provider takes the browser flow only, with no direct grants, service accounts or full scope, and authenticates by a key")
      for (id <- Seq("cfg-2026-0001-tre", "cfg-2026-0001-git")) {
        val c = client(id)
        assert(c.get("standardFlowEnabled").asBoolean, id)
        assert(!c.get("directAccessGrantsEnabled").asBoolean, id)
        assert(!c.get("serviceAccountsEnabled").asBoolean, id)
        assert(!c.get("fullScopeAllowed").asBoolean, id)
        assert(c.get("clientAuthenticatorType").asText == "client-jwt", id)
        assert(c.get("attributes").get("use.jwks.string").asText == "true", id)
      }
      And("each client has the exact redirect URIs of the project's TRE and Git")
      assert(values(client("cfg-2026-0001-tre").get("redirectUris")).toSet ==
        Set("https://jupyter.cfg-2026-0001.example.org/oauth2/callback", "https://jupyter.cfg-2026-0001.example.org/hub/oauth_callback"))
      assert(values(client("cfg-2026-0001-git").get("redirectUris")) ==
        Seq("https://git.cfg-2026-0001.example.org/user/oauth2/keycloak/callback"))
    }

    "have only a TRE client in the identity provider when Git is deployed with the TRE" in {
      Given("a TRE project with no Git of its own, registered in the identity provider")
      val project = projectOf("cfg-2026-0009", Seq("data1"), ownGit = false)
      provider.ensureProject(project, Map("tre" -> publicKeys().get("tre")).asJava)
      val ivy = provider.ensureUser("ivy@tre.example", "Ivy", "I")
      provider.addMember(project, ivy)

      Then("the identity provider has a TRE client for it, whose token names the project, and no Git client")
      assert(values(tokenOf("cfg-2026-0009-tre", ivy).get("project")) == Seq("cfg-2026-0009"))
      val found = get(s"/admin/realms/$KEYCLOAK_REALM/clients?clientId=cfg-2026-0009-git")
      assert(found.size() == 0)
    }

    "authenticate a project's client in the identity provider by the key that project-key made, and refuse a key that is not its own" in {
      Given("the key pair of a TRE project's client, made by the project-key script, whose public key the identity provider is given when the project is registered")
      val id = "cfg-2026-0010"
      val afonHome = Files.createTempDirectory("afon-home")
      runScript("project/key.sh", afonHome, id, "tre")
      val publicKey = Files.readString(afonHome.resolve(s"projects/$id/keys/tre.jwk"))
      val privateKey = privateKeyOf(afonHome.resolve(s"keys/$id/tre.pem"))
      provider.ensureProject(projectOf(id, Seq("data1"), ownGit = false), Map("tre" -> publicKey).asJava)
      // the token endpoint of a realm answers plain http from this machine only if the realm does not require https of it
      adminPut(s"/admin/realms/$KEYCLOAK_REALM", s"""{"realm":"$KEYCLOAK_REALM","sslRequired":"none"}""")
      val redirectUri = s"https://jupyter.$id.example.org/oauth2/callback"

      When("the TRE client presents the identity provider with an assertion signed with its private key")
      val authenticated = tokenEndpointAnswer(s"$id-tre", clientAssertion(s"$id-tre", privateKey), redirectUri)
      Then("the identity provider authenticates it, and what its token endpoint refuses is the code, which was never issued")
      assert(authenticated == ((400, "invalid_grant")), authenticated.toString)

      When("the client presents an assertion signed with another key")
      val other = KeyPairGenerator.getInstance("RSA")
      other.initialize(2048)
      val refused = tokenEndpointAnswer(s"$id-tre", clientAssertion(s"$id-tre", other.generateKeyPair().getPrivate), redirectUri)
      Then("the identity provider does not authenticate it")
      assert(refused._1 == 401 || refused._2.contains("client"), refused.toString)
      assert(refused != authenticated)
    }

    "give a member of the project a token naming the project, and a non-member none" in {
      Given("a TRE project with a Data Provider, registered in the identity provider, with a member in its group, and a user who is not a member")
      val project = projectOf("cfg-2026-0002", Seq("data1"))
      provider.ensureProject(project, publicKeys())
      val alice = provider.ensureUser("alice@tre.example", "Alice", "A")
      val bob = provider.ensureUser("bob@tre.example", "Bob", "B")
      provider.addMember(project, alice)

      When("the identity provider issues a token to each of them for the project's TRE client")
      val aliceToken = tokenOf("cfg-2026-0002-tre", alice)
      val bobToken = tokenOf("cfg-2026-0002-tre", bob)

      Then("the member's token names the project, once, and the user by their immutable id, with the audiences of the project's TRE client and of its Data Provider")
      assert(values(aliceToken.get("project")) == Seq("cfg-2026-0002"))
      assert(aliceToken.get("polaris_principal").asText == s"afon:$alice")
      assert(values(aliceToken.get("aud")).toSet == Set("cfg-2026-0002-tre", "provider-data1"))

      And("the non-member's token for the same TRE client names no project")
      assert(values(bobToken.get("project")).isEmpty)

      And("the Git client's token carries no audience of a Data Provider, so that Git cannot replay it at one")
      assert(values(tokenOf("cfg-2026-0002-git", alice).get("aud")) == Seq("cfg-2026-0002-git"))
      assert(values(tokenOf("cfg-2026-0002-git", alice).get("project")) == Seq("cfg-2026-0002"))
    }

    "give each user, in their tokens, the projects they are a member of and no other" in {
      Given("two TRE projects registered in the identity provider, alice a member of both, bob of the first only, and rob of the second only")
      val project1 = projectOf("cfg-2026-0007", Seq("data1"))
      val project2 = projectOf("cfg-2026-0008", Seq("data2"))
      provider.ensureProject(project1, publicKeys())
      provider.ensureProject(project2, publicKeys())
      val alice = provider.ensureUser("alice@tre.example", "Alice", "A")
      val bob = provider.ensureUser("bob@tre.example", "Bob", "B")
      val rob = provider.ensureUser("rob@tre.example", "Rob", "R")
      provider.addMember(project1, alice)
      provider.addMember(project2, alice)
      provider.addMember(project1, bob)
      provider.addMember(project2, rob)

      Then("the group of each project in the identity provider has its members, and no others")
      assert(groupMembers("cfg-2026-0007").toSet == Set(alice, bob))
      assert(groupMembers("cfg-2026-0008").toSet == Set(alice, rob))
      assert(provider.members(project1).keySet.asScala.toSet == Set(alice, bob))
      assert(provider.members(project2).keySet.asScala.toSet == Set(alice, rob))

      And("a token of each project's TRE client names that project for its members, and none for the others")
      def projectsOf(clientId: String, user: String) = values(tokenOf(clientId, user).get("project"))
      assert(projectsOf("cfg-2026-0007-tre", alice) == Seq("cfg-2026-0007"))
      assert(projectsOf("cfg-2026-0008-tre", alice) == Seq("cfg-2026-0008"))
      assert(projectsOf("cfg-2026-0007-tre", bob) == Seq("cfg-2026-0007"))
      assert(projectsOf("cfg-2026-0008-tre", bob).isEmpty)
      assert(projectsOf("cfg-2026-0007-tre", rob).isEmpty)
      assert(projectsOf("cfg-2026-0008-tre", rob) == Seq("cfg-2026-0008"))

      And("a user who is a member of both gets, in each project's token, the audience of that project's own Data Provider")
      assert(values(tokenOf("cfg-2026-0007-tre", alice).get("aud")).toSet == Set("cfg-2026-0007-tre", "provider-data1"))
      assert(values(tokenOf("cfg-2026-0008-tre", alice).get("aud")).toSet == Set("cfg-2026-0008-tre", "provider-data2"))

      When("alice is removed from the first project's group")
      provider.removeMember(project1, alice)
      Then("she is still a member of the second, and bob and rob are as they were")
      assert(projectsOf("cfg-2026-0007-tre", alice).isEmpty)
      assert(projectsOf("cfg-2026-0008-tre", alice) == Seq("cfg-2026-0008"))
      assert(groupMembers("cfg-2026-0007").toSet == Set(bob))
      assert(groupMembers("cfg-2026-0008").toSet == Set(alice, rob))
    }

    "follow the Data Providers of the project when it is registered again" in {
      Given("a TRE project with one Data Provider, registered in the identity provider, and a member")
      val before = projectOf("cfg-2026-0005", Seq("data1"))
      provider.ensureProject(before, publicKeys())
      val frank = provider.ensureUser("frank@tre.example", "Frank", "F")
      provider.addMember(before, frank)
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-data1"))

      When("it is registered again with another Data Provider added")
      provider.ensureProject(projectOf("cfg-2026-0005", Seq("data1", "data2")), publicKeys())
      Then("its TRE client's token carries the audience of both")
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-data1", "provider-data2"))

      When("it is registered again with the first Data Provider gone")
      provider.ensureProject(projectOf("cfg-2026-0005", Seq("data2")), publicKeys())
      Then("its TRE client's token no longer carries the audience of the first, and the member is still a member")
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-data2"))
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("project")) == Seq("cfg-2026-0005"))
    }

    "stop naming the project for a member removed from its group, and issue no token once the project is closed" in {
      Given("a TRE project registered in the identity provider, with a member in its group")
      val project = projectOf("cfg-2026-0006", Seq("data1"))
      provider.ensureProject(project, publicKeys())
      val alice = provider.ensureUser("alice@tre.example", "Alice", "A")
      provider.addMember(project, alice)
      assert(groupMembers("cfg-2026-0006").contains(alice))
      And("the identity provider lists the group's members by id, with their email")
      assert(provider.members(project).get(alice) == "alice@tre.example")

      When("the member is removed from the project's group in the identity provider")
      provider.removeMember(project, alice)
      Then("they are out of the group in the identity provider, and their token names no project")
      assert(!groupMembers("cfg-2026-0006").contains(alice))
      assert(values(tokenOf("cfg-2026-0006-tre", alice).get("project")).isEmpty)
      assert(!provider.members(project).containsKey(alice))

      When("the project is closed in the identity provider")
      provider.closeProject(project)
      Then("its TRE client is disabled, so that no token is issued for the project, and its Git client, which holds the code, is not")
      assert(!client("cfg-2026-0006-tre").get("enabled").asBoolean)
      assert(client("cfg-2026-0006-git").get("enabled").asBoolean)
    }

    "make a researcher in the identity provider once, with no password, to be invited" in {
      When("a researcher is made twice in the identity provider")
      val first = provider.ensureUser("bob@tre.example", "Bob", "B")
      val second = provider.ensureUser("bob@tre.example", "Bob", "B")
      Then("it is the same user, with the required actions of an invitation and no credentials")
      assert(first == second)
      assert(provider.findUser("bob@tre.example").orElse("") == first)
      val user = get(s"/admin/realms/$KEYCLOAK_REALM/users/$first")
      assert(values(user.get("requiredActions")).toSet == Set("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP"))
      assert(get(s"/admin/realms/$KEYCLOAK_REALM/users/$first/credentials").size() == 0)
      assert(!provider.findUser("nobody@tre.example").isPresent)
    }
  }
}

/** The projects of the central identity, on AWS. */
@Project
@ProjectIdentity
class ProjectIdentityAwsSpec extends AbstractProjectIdentitySpec(AfonCloud.AWS)

/** The projects of the central identity, on Azure. */
@Project
@ProjectIdentity
class ProjectIdentityAzureSpec extends AbstractProjectIdentitySpec(AfonCloud.AZURE)

/** The projects of the central identity, on GCP. */
@Project
@ProjectIdentity
class ProjectIdentityGcpSpec extends AbstractProjectIdentitySpec(AfonCloud.GCP)
