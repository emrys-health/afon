package health.emrys.afon.acceptance.project

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import health.emrys.afon.acceptance.ContainerHelper
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

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * The projects of the central identity, against a real KeyCloak, the one that Afon deploys, in a container: needs docker only.  The cloud of the projects
 * changes only the names of the properties of the cloud, so a subclass gives the cloud to run it for, see [[ProjectIdentityAwsSpec]].
 *
 * Each project has a client for its TRE and one for its Git, and a group.  A token of a project's client names that project, and
 * only when the user is in its group, and carries the audiences of the project's Data Providers on the TRE client alone.  Most tokens are
 * those that KeyCloak would issue, which its admin API generates for a user and a client without a browser login; the login of a project's client,
 * which admits only its members, after a second factor, is run as a browser would, with the one-time password of an authenticator that it enrols.
 */
abstract class ProjectIdentitySpec(cloud: AfonCloud) extends SpecPretifier with GivenWhenThen {

  private val mapper = new ObjectMapper()
  private val http = HttpClient.newHttpClient()
  private val AdminUser = "admin"
  private val AdminPassword = "admin"

  private lazy val keycloak: GenericContainer[_] = {
    val container: GenericContainer[_] = new GenericContainer[Nothing](DockerImages.KEYCLOAK_IMAGE)
    container.withNetwork(ContainerHelper.instance.network())
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
  /** The mail server, which the realm sends its invitations to and whose API gives them back, in a container of the helper that the KeyCloak shares a network with. */
  private lazy val mailpit: GenericContainer[_] = ContainerHelper.instance.startMailpit()
  private lazy val mailUrl = s"http://localhost:${mailpit.getMappedPort(ContainerHelper.MAILPIT_API_PORT)}"

  private lazy val provider: KeyCloakProvider = {
    mailpit // started first, so that the realm has a server to send its invitations to
    // the container may be one of an earlier run, or of another spec, with the emails of its own to the same addresses: this spec counts only its own
    http.send(HttpRequest.newBuilder(URI.create(s"$mailUrl/api/v1/messages")).DELETE().build(), BodyHandlers.ofString())
    val identity = new KeyCloakProvider(baseUrl, AdminUser, AdminPassword)
      .withSmtp(new KeyCloakProvider.Smtp(ContainerHelper.MAILPIT_ALIAS, ContainerHelper.MAILPIT_SMTP_PORT, "tre@example.org", KeyCloakProvider.Smtp.Security.NONE, null, null))
    identity.createRealm(identity.adminToken(), KEYCLOAK_REALM)
    identity
  }

  override def afterAll(): Unit = {
    try {
      if (keycloak != null) keycloak.stop()
    } finally super.afterAll()
  }

  /** The secret of each client of a project, as project-secret makes it: the identity provider and the TRE have the same. */
  private def secrets(): java.util.Map[String, String] = Map("tre" -> "tre-client-secret", "git" -> "git-client-secret").asJava

  private def projectOf(id: String, providers: Seq[String], ownGit: Boolean = true): TreProject = {
    val git = if (ownGit) s"\n         |  git: {domain: git.$id.example.org}" else ""
    val catalogs = providers.map(name =>
      s"    - {id: $name, catalogUri: 'https://polaris.$name.example/api', catalogName: $id, audience: provider-$name, bucket: $name-$id}").mkString("\n")
    FileProjectProvider.parse(
      s"""project: {id: $id, cloud: ${cloud.toString.toLowerCase}}
         |identity: {issuerUrl: 'https://id.example.org/realms/polaris', acr: mfa}
         |dataProviders:
         |  defined:
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

  /**
   * What the token endpoint answers a client that presents a code that was never issued, with its secret: KeyCloak authenticates the client before it
   * looks at the code, so that an answer about the code means that the client was authenticated, and one about the client that it was not.
   */
  private def tokenEndpointAnswer(clientId: String, secret: String, redirectUri: String): (Int, String) = {
    def enc(value: String) = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
    val form = Seq("grant_type" -> "authorization_code", "code" -> "never-issued", "redirect_uri" -> redirectUri, "client_id" -> clientId,
                   "client_secret" -> secret)
      .map { case (k, v) => s"${enc(k)}=${enc(v)}" }.mkString("&")
    val request = HttpRequest.newBuilder(URI.create(s"$baseUrl/realms/$KEYCLOAK_REALM/protocol/openid-connect/token"))
      .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()
    val response = http.send(request, BodyHandlers.ofString())
    (response.statusCode(), mapper.readTree(response.body()).path("error").asText())
  }


  /**
   * A browser, as far as a login goes: it keeps the cookies that KeyCloak sets, whichever their attributes, as the realm is plain http here, and follows no
   * redirect of itself, so that the one to the client's redirect URI, which carries the code, is seen.
   */
  private final class Browser {
    private val cookies = mutable.LinkedHashMap.empty[String, String]
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    def send(request: HttpRequest.Builder): HttpResponse[String] = {
      if (cookies.nonEmpty) request.header("Cookie", cookies.map { case (k, v) => s"$k=$v" }.mkString("; "))
      val response = client.send(request.build(), BodyHandlers.ofString())
      response.headers().allValues("Set-Cookie").forEach { cookie =>
        val pair = cookie.takeWhile(_ != ';')
        val at = pair.indexOf('=')
        val (name, value) = (pair.take(at), pair.drop(at + 1))
        if (value.isEmpty) cookies.remove(name) else cookies(name) = value
      }
      response
    }
  }

  /** What a login ended with: the code that it was given for the client's redirect URI, or else the page that it stopped at, and the secret of the authenticator that it enrolled. */
  private case class LoginResult(code: Option[String], page: String, totpSecret: Option[String])

  private val usedCounters = mutable.Set.empty[(String, Long)]

  /** The current one-time password of an authenticator, one that was not used already, as KeyCloak refuses a code twice. */
  private def totp(secret: String): String = {
    val now = System.currentTimeMillis() / 30000
    val counter = Seq(now, now + 1, now - 1).find(c => !usedCounters.contains((secret, c))).getOrElse {
      Thread.sleep(31000)
      System.currentTimeMillis() / 30000
    }
    usedCounters += ((secret, counter))
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"))
    val hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array())
    val offset = hash(hash.length - 1) & 0xf
    val binary = ((hash(offset) & 0x7f) << 24) | ((hash(offset + 1) & 0xff) << 16) | ((hash(offset + 2) & 0xff) << 8) | (hash(offset + 3) & 0xff)
    "%06d".format(binary % 1000000)
  }

  private def form(fields: Seq[(String, String)]): HttpRequest.BodyPublisher =
    HttpRequest.BodyPublishers.ofString(fields.map { case (k, v) => s"${java.net.URLEncoder.encode(k, StandardCharsets.UTF_8)}=${java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)}" }.mkString("&"))

  private def textOf(page: String): String = page.replaceAll("(?s)<(script|style).*?</\\1>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim

  /**
   * Logs a user in to a project's client by the browser flow, as a browser would: the page of the password, then, if the user may go on, the page of
   * the second factor, which enrols an authenticator the first time and asks for its code after.
   *
   * @param totpSecret the secret of the user's authenticator, if they have enrolled one
   */
  private def login(browser: Browser, clientId: String, redirectUri: String, challenge: String, username: String, password: String,
                    totpSecret: Option[String], acrValues: Option[String] = None): LoginResult = {
    val query = (Seq("client_id" -> clientId, "redirect_uri" -> redirectUri, "response_type" -> "code", "scope" -> "openid", "state" -> "state",
                    "code_challenge" -> challenge, "code_challenge_method" -> "S256") ++ acrValues.map("acr_values" -> _))
      .map { case (k, v) => s"$k=${java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)}" }.mkString("&")
    var secret = totpSecret
    var response = browser.send(HttpRequest.newBuilder(URI.create(s"$baseUrl/realms/$KEYCLOAK_REALM/protocol/openid-connect/auth?$query")).GET())
    for (_ <- 1 to 10) {
      if (response.statusCode() / 100 == 3) {
        val location = URI.create(baseUrl).resolve(response.headers().firstValue("Location").orElseThrow())
        if (location.toString.startsWith(redirectUri)) {
          val code = location.getRawQuery.split("&").map(_.split("=", 2)).collectFirst { case Array("code", value) => value }
          return LoginResult(code, "", secret)
        }
        response = browser.send(HttpRequest.newBuilder(location).GET())
      } else {
        val page = response.body()
        val action = "(?s)<form[^>]*\\saction=\"([^\"]+)\"".r.findFirstMatchIn(page).map(_.group(1).replace("&amp;", "&"))
        val fields: Option[Seq[(String, String)]] =
          if (page.contains("name=\"username\"")) Some(Seq("username" -> username, "password" -> password))
          else if (page.contains("name=\"totpSecret\"")) {
            secret = "name=\"totpSecret\" value=\"([^\"]+)\"".r.findFirstMatchIn(page).map(_.group(1))
            Some(Seq("totp" -> totp(secret.get), "totpSecret" -> secret.get, "userLabel" -> "phone"))
          } else if (page.contains("name=\"otp\"") && secret.isDefined) Some(Seq("otp" -> totp(secret.get)))
          else None
        if (action.isEmpty || fields.isEmpty) return LoginResult(None, textOf(page.substring(page.indexOf("kc-error-message").max(0))), secret)
        response = browser.send(HttpRequest.newBuilder(URI.create(action.get)).header("Content-Type", "application/x-www-form-urlencoded")
                                  .POST(form(fields.get)))
      }
    }
    LoginResult(None, "the login did not end", secret)
  }

  /** The claims of the access token that a client is given for a code, which it presents with its secret, and the verifier of its challenge. */
  private def exchange(clientId: String, secret: String, code: String, redirectUri: String, verifier: String): JsonNode = {
    val request = HttpRequest.newBuilder(URI.create(s"$baseUrl/realms/$KEYCLOAK_REALM/protocol/openid-connect/token"))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(form(Seq("grant_type" -> "authorization_code", "code" -> code, "redirect_uri" -> redirectUri, "client_id" -> clientId, "code_verifier" -> verifier,
                     "client_secret" -> secret))).build()
    val response = http.send(request, BodyHandlers.ofString())
    assert(response.statusCode() == 200, s"token endpoint: HTTP ${response.statusCode()} ${response.body()}")
    mapper.readTree(Base64.getUrlDecoder.decode(mapper.readTree(response.body()).get("access_token").asText().split("\\.")(1)))
  }

  /** A challenge of the S256 method, and the verifier that it is of. */
  private def pkce(): (String, String) = {
    val verifier = Base64.getUrlEncoder.withoutPadding().encodeToString(java.util.UUID.randomUUID().toString.getBytes(StandardCharsets.UTF_8))
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))
    (Base64.getUrlEncoder.withoutPadding().encodeToString(digest), verifier)
  }

  /** The emails that the mail server has for an address, the newest first. */
  private def messagesTo(email: String): Seq[JsonNode] =
    mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(s"$mailUrl/api/v1/messages")).GET().build(), BodyHandlers.ofString()).body())
      .get("messages").elements().asScala.filter(m => m.get("To").elements().asScala.exists(_.get("Address").asText == email)).toSeq

  /** Waits a little for the mail server to have at least this many emails for an address. */
  private def awaitMessagesTo(email: String, count: Int): Seq[JsonNode] = {
    val deadline = System.currentTimeMillis() + 15000
    var found = messagesTo(email)
    while (found.size < count && System.currentTimeMillis() < deadline) { Thread.sleep(500); found = messagesTo(email) }
    assert(found.size >= count, s"$count email(s) to $email expected at the mail server, and it has ${found.size}")
    found
  }

  /** The first link of the newest email that the mail server has for an address, which it is waited for a little. */
  private def linkOfInvitationTo(email: String): String = {
    val found = awaitMessagesTo(email, 1)
    val text = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(s"$mailUrl/api/v1/message/${found.head.get("ID").asText}")).GET().build(),
                                         BodyHandlers.ofString()).body()).get("Text").asText()
    "https?://\\S+".r.findFirstIn(text).getOrElse(fail(s"no link in the email to $email: $text"))
  }

  /**
   * A member accepts the invitation that the identity provider emailed them, as in a browser: they follow its link, enrol an authenticator, set a password,
   * and the identity provider marks their email verified.
   *
   * @return the secret of the authenticator that they enrolled
   */
  private def acceptInvitation(email: String, password: String): String = {
    val browser = new Browser
    var secret: Option[String] = None
    var response = browser.send(HttpRequest.newBuilder(URI.create(linkOfInvitationTo(email))).GET())
    for (_ <- 1 to 10 if response != null) {
      if (response.statusCode() / 100 == 3) {
        response = browser.send(HttpRequest.newBuilder(URI.create(baseUrl).resolve(response.headers().firstValue("Location").orElseThrow())).GET())
      } else {
        val page = response.body()
        val action = "(?s)<form[^>]*\\saction=\"([^\"]+)\"".r.findFirstMatchIn(page).map(_.group(1).replace("&amp;", "&"))
        val link = "href=\"([^\"]*login-actions[^\"]*)\"".r.findFirstMatchIn(page).map(_.group(1).replace("&amp;", "&"))
        if (action.isDefined) {
          val fields =
            if (page.contains("name=\"password-new\"")) Seq("password-new" -> password, "password-confirm" -> password)
            else if (page.contains("name=\"totpSecret\"")) {
              secret = "name=\"totpSecret\" value=\"([^\"]+)\"".r.findFirstMatchIn(page).map(_.group(1))
              Seq("totp" -> totp(secret.get), "totpSecret" -> secret.get, "userLabel" -> "phone")
            } else Seq.empty
          response = browser.send(HttpRequest.newBuilder(URI.create(action.get)).header("Content-Type", "application/x-www-form-urlencoded").POST(form(fields)))
        } else if (link.isDefined) {
          response = browser.send(HttpRequest.newBuilder(URI.create(link.get)).GET())
        } else response = null
      }
    }
    assert(secret.isDefined, s"the invitation to $email did not have them enrol an authenticator")
    secret.get
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

    "have a TRE client and a Git client in the identity provider, which take no password and authenticate by secret" in {
      Given("a TRE project with a Git of its own, registered in the identity provider")
      val project = projectOf("cfg-2026-0001", Seq("data1"))
      provider.ensureProject(project, secrets())

      Then("each of its two clients in the identity provider takes the browser flow only, with no direct grants, service accounts or full scope, and authenticates by a secret")
      for (id <- Seq("cfg-2026-0001-tre", "cfg-2026-0001-git")) {
        val c = client(id)
        assert(c.get("standardFlowEnabled").asBoolean, id)
        assert(!c.get("directAccessGrantsEnabled").asBoolean, id)
        assert(!c.get("serviceAccountsEnabled").asBoolean, id)
        assert(!c.get("fullScopeAllowed").asBoolean, id)
        assert(c.get("clientAuthenticatorType").asText == "client-secret", id)
        assert(c.get("attributes").path("use.jwks.string").isMissingNode, id)
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
      provider.ensureProject(project, Map("tre" -> secrets().get("tre")).asJava)
      val ivy = provider.ensureUser("ivy@tre.example", "Ivy", "I")
      provider.addMember(project, ivy)

      Then("the identity provider has a TRE client for it, whose token names the project, and no Git client")
      assert(values(tokenOf("cfg-2026-0009-tre", ivy).get("project")) == Seq("cfg-2026-0009"))
      val found = get(s"/admin/realms/$KEYCLOAK_REALM/clients?clientId=cfg-2026-0009-git")
      assert(found.size() == 0)
    }

    "authenticate a project's client in the identity provider by its secret, and refuse a secret that is not its own" in {
      Given("a TRE project's client, whose secret the identity provider is given when the project is registered")
      val id = "cfg-2026-0010"
      val secret = "secret-that-project-secret-made"
      provider.ensureProject(projectOf(id, Seq("data1"), ownGit = false), Map("tre" -> secret).asJava)
      // the token endpoint of a realm answers plain http from this machine only if the realm does not require https of it
      adminPut(s"/admin/realms/$KEYCLOAK_REALM", s"""{"realm":"$KEYCLOAK_REALM","sslRequired":"none"}""")
      val redirectUri = s"https://jupyter.$id.example.org/oauth2/callback"

      When("the TRE client presents the identity provider with its secret")
      val authenticated = tokenEndpointAnswer(s"$id-tre", secret, redirectUri)
      Then("the identity provider authenticates it, and what its token endpoint refuses is the code, which was never issued")
      assert(authenticated == ((400, "invalid_grant")), authenticated.toString)

      When("the client presents another secret")
      val refused = tokenEndpointAnswer(s"$id-tre", "another-secret", redirectUri)
      Then("the identity provider does not authenticate it")
      assert(refused._1 == 401 || refused._2.contains("client"), refused.toString)
      assert(refused != authenticated)
    }

    "give a member of the project a token naming the project, and a non-member none" in {
      Given("a TRE project with a Data Provider, registered in the identity provider, with a member in its group, and a user who is not a member")
      val project = projectOf("cfg-2026-0002", Seq("data1"))
      provider.ensureProject(project, secrets())
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
      provider.ensureProject(project1, secrets())
      provider.ensureProject(project2, secrets())
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
      provider.ensureProject(before, secrets())
      val frank = provider.ensureUser("frank@tre.example", "Frank", "F")
      provider.addMember(before, frank)
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-data1"))

      When("it is registered again with another Data Provider added")
      provider.ensureProject(projectOf("cfg-2026-0005", Seq("data1", "data2")), secrets())
      Then("its TRE client's token carries the audience of both")
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-data1", "provider-data2"))

      When("it is registered again with the first Data Provider gone")
      provider.ensureProject(projectOf("cfg-2026-0005", Seq("data2")), secrets())
      Then("its TRE client's token no longer carries the audience of the first, and the member is still a member")
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("aud")).toSet == Set("cfg-2026-0005-tre", "provider-data2"))
      assert(values(tokenOf("cfg-2026-0005-tre", frank).get("project")) == Seq("cfg-2026-0005"))
    }

    "stop naming the project for a member removed from its group, and issue no token once the project is closed" in {
      Given("a TRE project registered in the identity provider, with a member in its group")
      val project = projectOf("cfg-2026-0006", Seq("data1"))
      provider.ensureProject(project, secrets())
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

    "make a member in the identity provider once, with no password, to be invited" in {
      When("a member is made twice in the identity provider")
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

    "send the invitation again to a member who is in the identity provider, who can accept it, and refuse one who is not" in {
      Given("a member made in the identity provider, who has been sent the invitation once")
      provider.ensureUser("rob@tre.example", "Rob", "R")
      awaitMessagesTo("rob@tre.example", 1)

      When("the invitation is sent again")
      provider.invite("rob@tre.example")

      Then("the mail server has a second email for them, and following its link has them enrol an authenticator")
      assert(awaitMessagesTo("rob@tre.example", 2).size == 2)
      assert(acceptInvitation("rob@tre.example", "rob-password").nonEmpty)

      When("it is sent to someone who is not in the identity provider")
      Then("it fails, and sends nothing")
      val error = intercept[IllegalStateException](provider.invite("nobody@tre.example"))
      assert(error.getMessage.contains("nobody@tre.example"))
      assert(messagesTo("nobody@tre.example").isEmpty)
    }

    "log a member in to the project's TRE client after a second factor, and refuse a user who is not a member, even with the session of another project" in {
      Given("two TRE projects registered in the identity provider, the first with alice as a member and the second with bob")
      val (first, second) = ("cfg-2026-0011", "cfg-2026-0012")
      val afonHome = Files.createTempDirectory("afon-home")
      runScript("project/secret.sh", afonHome, first, "tre")
      val secret = Files.readString(afonHome.resolve(s"projects/$first/keys/tre.secret")).strip()
      val firstProject = projectOf(first, Seq("data1"), ownGit = false)
      val secondProject = projectOf(second, Seq("data1"), ownGit = false)
      provider.ensureProject(firstProject, Map("tre" -> secret).asJava)
      provider.ensureProject(secondProject, Map("tre" -> secret).asJava)
      adminPut(s"/admin/realms/$KEYCLOAK_REALM", s"""{"realm":"$KEYCLOAK_REALM","sslRequired":"none"}""")
      val alice = provider.ensureUser("alice@tre.example", "Alice", "A")
      val bob = provider.ensureUser("bob@tre.example", "Bob", "B")
      And("alice and bob have accepted the invitations that the identity provider emailed them, setting their passwords and enrolling their authenticators")
      val aliceSecret = acceptInvitation("alice@tre.example", "alice-password")
      val bobSecret = acceptInvitation("bob@tre.example", "bob-password")
      provider.addMember(firstProject, alice)
      provider.addMember(secondProject, bob)
      val redirectUri = s"https://jupyter.$first.example.org/oauth2/callback"
      val (challenge, verifier) = pkce()

      When("alice logs in to the first project's TRE client, with their password and the code of their authenticator")
      val browser = new Browser
      val aliceLogin = login(browser, s"$first-tre", redirectUri, challenge, "alice@tre.example", "alice-password", Some(aliceSecret))
      Then("the identity provider gives the client a code, whose token says that a second factor was used, and names the project, the user and the audiences")
      assert(aliceLogin.code.isDefined, aliceLogin.page)
      val token = exchange(s"$first-tre", secret, aliceLogin.code.get, redirectUri, verifier)
      assert(token.get("acr").asText == "mfa", token.toString)
      assert(values(token.get("project")) == Seq(first))
      assert(token.get("polaris_principal").asText == s"afon:$alice")
      assert(values(token.get("aud")).toSet == Set(s"$first-tre", "provider-data1"))

      When("alice logs in again, with a new session")
      val again = login(new Browser, s"$first-tre", redirectUri, challenge, "alice@tre.example", "alice-password", Some(aliceSecret))
      Then("they are asked for the code of their authenticator, and the token says as much")
      assert(again.code.isDefined, again.page)
      assert(exchange(s"$first-tre", secret, again.code.get, redirectUri, verifier).get("acr").asText == "mfa")

      When("alice asks, in the request that starts the login, for a level of authentication below the second factor")
      val lowered = login(new Browser, s"$first-tre", redirectUri, challenge, "alice@tre.example", "alice-password", Some(aliceSecret), Some("1"))
      Then("the identity provider still asks for the second factor, or gives no code: no token comes of a password alone")
      assert(lowered.code.isEmpty || exchange(s"$first-tre", secret, lowered.code.get, redirectUri, verifier).get("acr").asText == "mfa",
             s"a code of a password alone: $lowered")

      When("bob, who is not a member of the first project, logs in to its TRE client with the right password")
      val bobLogin = login(new Browser, s"$first-tre", redirectUri, challenge, "bob@tre.example", "bob-password", Some(bobSecret))
      Then("the identity provider denies them, and gives the client no code")
      assert(bobLogin.code.isEmpty)
      assert(bobLogin.page.contains("You are not a member of this project"), bobLogin.page)

      When("alice, who has a session of the first project, logs in to the second project's TRE client, of which they are not a member")
      val denied = login(browser, s"$second-tre", s"https://jupyter.$second.example.org/oauth2/callback", challenge, "alice@tre.example", "alice-password",
                         Some(aliceSecret))
      Then("the identity provider denies them too: a session of one project is no way into another")
      assert(denied.code.isEmpty)
      assert(denied.page.contains("You are not a member of this project"), denied.page)
    }
  }
}

/**
 * The projects of the central identity, run for AWS alone: they are tested against KeyCloak, which does not know the cloud of a project, and the only thing
 * that the cloud changes, the name of the domain property that the redirect URIs are made from, is tested for each cloud by the unit tests of the provider.
 */
@Project
@ProjectIdentity
class ProjectIdentityAwsSpec extends ProjectIdentitySpec(AfonCloud.AWS)
