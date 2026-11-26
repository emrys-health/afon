package health.emrys.afon.acceptance.project

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.tags.{Project, ProjectIdentity}
import health.emrys.afon.cdk.DockerImages
import health.emrys.afon.cdk.configs.PolarisConfig
import health.emrys.afon.config.{AfonCloud, AfonUserProvider}
import health.emrys.afon.polaris.config.{CatalogConfigurer, PolarisProjectGrants, PolarisRestClient}
import health.emrys.afon.polaris.keycloak.KeyCloakHelper
import health.emrys.afon.polaris.keycloak.KeyCloakHelper.Trust
import health.emrys.afon.project.{FileProjectStackProvider, TreProjectStack}
import health.emrys.afon.security.keycloak.KeyCloakConfigurer.KEYCLOAK_REALM
import health.emrys.afon.security.keycloak.KeyCloakProvider
import org.scalatest.GivenWhenThen
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{GenericContainer, Network}

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.jdk.CollectionConverters._

/**
 * What a Data Provider grants a project, against a real Polaris: the Polaris of a Data Provider, in a container, that trusts the tokens of the central
 * identity, also in one, and the members of a project, who sign in to it as they would, with the token that the identity issues them.  The grants
 * are those of {@code manage-data}: the Data Provider lets a project in, and names its members, and neither is for the identity's side to do.
 * Needs docker only.
 */
abstract class ProjectDataAccessSpec(cloud: AfonCloud) extends SpecPretifier with GivenWhenThen {

  private val mapper = new ObjectMapper()
  private val http = HttpClient.newHttpClient()
  private val AdminUser = "admin"
  private val AdminPassword = "admin"
  private val RootId = "root"
  private val RootSecret = "root-secret-1234"
  private val Project = "cfg-2026-0020"
  private val OtherProject = "cfg-2026-0021"
  private val Catalog = "cvd"
  private val OtherCatalog = "dbt"
  /** The audience of the Data Provider, which its Polaris requires of every token. */
  private val Audience = "provider-lon"
  private val KeycloakAlias = "keycloak"

  private lazy val network: Network = Network.newNetwork()

  private lazy val keycloak: GenericContainer[_] = {
    val container: GenericContainer[_] = new GenericContainer[Nothing](DockerImages.KEYCLOAK_IMAGE)
    container.withNetwork(network)
    container.withNetworkAliases(KeycloakAlias)
    container.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", AdminUser)
    container.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", AdminPassword)
    container.withEnv("KC_HTTP_ENABLED", "true")
    container.withEnv("KC_HOSTNAME_STRICT", "false")
    container.withExposedPorts(Integer.valueOf(8080))
    container.withCommand("start-dev")
    container.waitingFor(Wait.forHttp("/realms/master").forPort(8080).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
    container.start()
    container
  }

  private lazy val keycloakUrl = s"http://localhost:${keycloak.getMappedPort(8080)}"

  private lazy val identity: KeyCloakProvider = {
    val provider = new KeyCloakProvider(keycloakUrl, AdminUser, AdminPassword)
    // the Polaris of the Data Provider is a client of the identity, as the service that makes its catalogs is
    provider.registerPolarisUser(AfonUserProvider.createUser(RootId, RootSecret))
    // whose tokens carry the Data Provider's audience too, as the Polaris of a Data Provider requires it of every token, its admins' included
    provider.ensureClientAudience(RootId, Audience)
    // and says it has the second factor, which a service has none to give: as the setup of the Data Provider's Polaris makes its admin client
    provider.ensureClientAcr(RootId, "mfa")
    // the realm answers plain http from this machine and from the other container only if it does not require https of them
    adminPut(provider, s"/admin/realms/$KEYCLOAK_REALM", s"""{"realm":"$KEYCLOAK_REALM","sslRequired":"none"}""")
    provider
  }

  /** A Polaris that trusts the identity, which keeps its catalogs in the files of its own container, so that no cloud is needed. */
  private lazy val polaris: GenericContainer[_] = {
    identity
    val container: GenericContainer[_] = new GenericContainer[Nothing](DockerImages.POLARIS_IMAGE)
    container.withNetwork(network)
    container.withExposedPorts(Integer.valueOf(8181), Integer.valueOf(8182))
    // the catalogs are in the files of the container: the type of storage that is for tests, which Polaris has to be told that it may have.  The names of
    // the list is quoted in its configuration, which the double underscores of an environment variable are for
    container.withEnv("polaris.features.ALLOW_INSECURE_STORAGE_TYPES", "true")
    container.withEnv("POLARIS_FEATURES__SUPPORTED_CATALOG_STORAGE_TYPES_", """["FILE","S3"]""")
    // and which it refuses to start with unless it is told that the severe issue is known: this is a test, not a deployment
    container.withEnv("polaris.readiness.ignore-severe-issues", "true")
    container.withEnv("POLARIS_BOOTSTRAP_CREDENTIALS", s"POLARIS,$RootId,$RootSecret")
    // it trusts the identity as the Polaris of a Data Provider does: the issuer that the tokens carry, which is the host that this machine reaches it at, the Data Provider's
    // audience, and the second factor; and its roles are those that the tokens name.  The names are those of the variables that the stack sets
    KeyHelper.oidcEnv(s"http://$KeycloakAlias:8080/realms/$KEYCLOAK_REALM", RootId, RootSecret,
                      new Trust(s"$keycloakUrl/realms/$KEYCLOAK_REALM", Audience, "mfa", "project"))
      .foreach { case (k, v) => container.withEnv(PolarisConfig.toEnvironmentVariable(k), v) }
    container.waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
    container.start()
    container
  }

  private lazy val polarisUrl = s"http://localhost:${polaris.getMappedPort(8181)}"

  /** The client of the Data Provider's own admin, which signs in to the identity as the service that it is, and calls Polaris with the token. */
  private lazy val adminClient: PolarisRestClient = new PolarisRestClient(polarisUrl, RootId, RootSecret) {
    override def getAccessToken: String = identity.clientToken(RootId, RootSecret)
  }

  private lazy val grants = new PolarisProjectGrants(new CatalogConfigurer(adminClient))

  override def afterAll(): Unit = {
    try {
      if (polaris != null) polaris.stop()
      if (keycloak != null) keycloak.stop()
    } finally super.afterAll()
  }

  private object KeyHelper {
    def oidcEnv(url: String, id: String, secret: String, trust: Trust): Map[String, String] =
      KeyCloakHelper.polarisOidcEnv(url, id, secret, trust).asScala.toMap
  }

  private def adminPut(provider: KeyCloakProvider, path: String, json: String): Unit = {
    val request = HttpRequest.newBuilder(URI.create(keycloakUrl + path)).header("Authorization", "Bearer " + provider.adminToken())
      .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)).build()
    val response = http.send(request, BodyHandlers.ofString())
    assert(response.statusCode() / 100 == 2, s"PUT $path: HTTP ${response.statusCode()} ${response.body()}")
    ()
  }

  private def adminGet(path: String): JsonNode = {
    val request = HttpRequest.newBuilder(URI.create(keycloakUrl + path)).header("Authorization", "Bearer " + identity.adminToken()).GET().build()
    val response = http.send(request, BodyHandlers.ofString())
    assert(response.statusCode() == 200, s"GET $path: HTTP ${response.statusCode()} ${response.body()}")
    mapper.readTree(response.body())
  }

  /** The secret of the TRE client, as project-secret makes it: 256 bits as hex, which is what the identity provider is given. */
  private def clientSecret(): String = java.util.UUID.randomUUID().toString.replace("-", "") + java.util.UUID.randomUUID().toString.replace("-", "")

  private def treProject(id: String, provider: String = "lon"): TreProjectStack =
    FileProjectStackProvider.parse(
      s"""projectStack: {id: $id, cloud: ${cloud.toString.toLowerCase}}
         |identity: {issuerUrl: 'https://id.example.org/realms/polaris', acr: mfa}
         |dataProviders:
         |  defined:
         |    - {id: $provider, catalogUri: 'https://polaris.$provider.example/api/catalog', catalogName: $Catalog, audience: provider-$provider, bucket: $provider}
         |members: [ada@example.org]
         |stacks:
         |  create: {auditBucket: $id-audit}
         |  tre: {domains: {${cloud.name}_JUPYTER_DOMAIN_NAME: jupyter.$id.example.org}}
         |""".stripMargin).asInstanceOf[TreProjectStack]

  private def userId(userName: String): String = adminGet(s"/admin/realms/$KEYCLOAK_REALM/users?exact=true&username=$userName").get(0).get("id").asText()

  /**
   * A member of the project, made as the identity makes one, who can then sign in at once: the invitation, which sets a password and a second factor, is for
   * the identity's side, and here a password is set and nothing is left to do.  Their token is that of the project's TRE client, which takes a password here
   * only, so that no browser is needed.
   */
  private def member(project: TreProjectStack, email: String): String = {
    val id = identity.ensureUser(email, "Test", "User")
    identity.addMember(project, id)
    adminPut(identity, s"/admin/realms/$KEYCLOAK_REALM/users/$id/reset-password", s"""{"type":"password","value":"$email-pw","temporary":false}""")
    val user = adminGet(s"/admin/realms/$KEYCLOAK_REALM/users/$id").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    user.putArray("requiredActions")
    user.put("emailVerified", true)
    adminPut(identity, s"/admin/realms/$KEYCLOAK_REALM/users/$id", mapper.writeValueAsString(user))
    id
  }

  /** The TRE client takes a password and a secret in this test, instead of the browser login and the key: the token that the identity issues is the same. */
  private def letTheClientTakeAPassword(project: TreProjectStack, secondFactor: Boolean = true): Unit = {
    val uuid = adminGet(s"/admin/realms/$KEYCLOAK_REALM/clients?clientId=${project.identity().treClient()}").get(0).get("id").asText()
    val client = adminGet(s"/admin/realms/$KEYCLOAK_REALM/clients/$uuid").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
    client.put("clientAuthenticatorType", "client-secret")
    client.put("secret", "tre-secret")
    client.put("directAccessGrantsEnabled", true)
    adminPut(identity, s"/admin/realms/$KEYCLOAK_REALM/clients/$uuid", mapper.writeValueAsString(client))
    if (secondFactor) standInForTheSecondFactor(identity, uuid, "mfa")
  }

  /**
   * The password grant gives a token of the first level of authentication, whatever the realm asks of a browser, so that the {@code acr} that the browser login
   * gives is set by a mapper here: the token is then that of a login with a second factor, which is what the Data Provider's Polaris requires of it.
   */
  private def standInForTheSecondFactor(provider: KeyCloakProvider, clientUuid: String, acr: String): Unit = {
    val mapperJson =
      s"""{"name":"stand-in-acr","protocol":"openid-connect","protocolMapper":"oidc-hardcoded-claim-mapper","config":{"claim.name":"acr","claim.value":"$acr",
         |"jsonType.label":"String","access.token.claim":"true","id.token.claim":"false","introspection.token.claim":"true"}}""".stripMargin.replace("\n", "")
    val request = HttpRequest.newBuilder(URI.create(s"$keycloakUrl/admin/realms/$KEYCLOAK_REALM/clients/$clientUuid/protocol-mappers/models"))
      .header("Authorization", "Bearer " + provider.adminToken()).header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(mapperJson)).build()
    // the built-in mapper of the realm, from the client scope of the acr, would say the first level over it
    val scopes = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(s"$keycloakUrl/admin/realms/$KEYCLOAK_REALM/client-scopes"))
      .header("Authorization", "Bearer " + provider.adminToken()).GET().build(), BodyHandlers.ofString()).body())
    scopes.elements().asScala.filter(_.get("name").asText() == "acr").foreach { scope =>
      http.send(HttpRequest.newBuilder(URI.create(s"$keycloakUrl/admin/realms/$KEYCLOAK_REALM/clients/$clientUuid/default-client-scopes/${scope.get("id").asText()}"))
        .header("Authorization", "Bearer " + provider.adminToken()).DELETE().build(), BodyHandlers.ofString())
    }
    val response = http.send(request, BodyHandlers.ofString())
    assert(response.statusCode() / 100 == 2, s"stand-in acr mapper: HTTP ${response.statusCode()} ${response.body()}")
    ()
  }

  private def tokenOf(project: TreProjectStack, email: String): String = {
    val form = Seq("grant_type" -> "password", "client_id" -> project.identity().treClient(), "client_secret" -> "tre-secret", "username" -> email,
                   "password" -> s"$email-pw").map { case (k, v) => s"$k=${java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)}" }.mkString("&")
    val request = HttpRequest.newBuilder(URI.create(s"$keycloakUrl/realms/$KEYCLOAK_REALM/protocol/openid-connect/token"))
      .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()
    val response = http.send(request, BodyHandlers.ofString())
    assert(response.statusCode() == 200, s"token of $email: HTTP ${response.statusCode()} ${response.body()}")
    mapper.readTree(response.body()).get("access_token").asText()
  }

  /** What Polaris answers a call of the catalog API with the token, as the engine of a TRE would make it. */
  private def callCatalog(method: String, path: String, token: String, body: String = null, catalog: String = Catalog): (Int, String) = {
    val builder = HttpRequest.newBuilder(URI.create(s"$polarisUrl/api/catalog/v1/$catalog$path")).header("Authorization", "Bearer " + token)
      .header("Content-Type", "application/json")
    val request = method match {
      case "GET" => builder.GET().build()
      case "POST" => builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
    }
    val response = http.send(request, BodyHandlers.ofString())
    (response.statusCode(), response.body())
  }

  private def canRead(token: String, namespace: String = "released", catalog: String = Catalog): Boolean =
    callCatalog("GET", s"/namespaces/$namespace", token, null, catalog)._1 == 200

  private def makeCatalog(name: String = Catalog): Unit = {
    val catalog =
      s"""{"catalog":{"type":"INTERNAL","name":"$name","properties":{"default-base-location":"file:///var/tmp/$name"},
         |"storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///var/tmp/$name"]}}}""".stripMargin.replace("\n", "")
    val created = adminClient.post(CatalogConfigurer.catalogsURI(), catalog, adminClient.getAccessToken)
    assert(created.getCode / 100 == 2, s"create the catalog $name: HTTP ${created.getCode}")
    ()
  }

  /** What the tests share: the project, registered in the identity, and its members alice and bob, who are both in its group, with the tokens that the identity issues them. */
  private case class World(project: TreProjectStack, alice: String, bob: String, aliceToken: String, bobToken: String)

  private lazy val world: World = {
    polaris
    makeCatalog()
    val project = treProject(Project)
    identity.ensureProject(project, Map("tre" -> clientSecret()).asJava)
    letTheClientTakeAPassword(project)
    val alice = member(project, "alice@tre.example")
    val bob = member(project, "bob@tre.example")
    World(project, alice, bob, tokenOf(project, "alice@tre.example"), tokenOf(project, "bob@tre.example"))
  }

  private def status(method: String, path: String, token: String, body: String = null): Int = callCatalog(method, path, token, body)._1

  private def tableRequest(name: String) =
    s"""{"name":"$name","schema":{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}}"""

  private def tablesIn(namespace: String, token: String): Seq[String] = {
    val (code, body) = callCatalog("GET", s"/namespaces/$namespace/tables", token)
    assert(code == 200, s"list the tables of $namespace: HTTP $code $body")
    mapper.readTree(body).get("identifiers").elements().asScala.map(_.get("name").asText()).toSeq
  }

  "A Data Provider" should {

    "let in only the project and the members it grants, and no others" in {
      Given("a Data Provider with a catalog, and a TRE project registered in the central identity, with alice and bob in its group")
      val w = world
      assert(!canRead(w.aliceToken) && !canRead(w.bobToken), "the identity's tokens give no access to a Data Provider that has granted nothing")

      When("the Data Provider grants the project the catalog, naming alice as its member")
      grants.grant(Project, Catalog, "released", java.util.List.of(w.alice))

      Then("alice reads the released namespace, and bob, who is in the project's group but not named by the Data Provider, does not")
      assert(canRead(w.aliceToken, "released"))
      assert(!canRead(w.bobToken, "released"))
      And("alice cannot read the namespace where the Data Provider checks its tables")
      assert(!canRead(w.aliceToken, "staging"))
    }

    "let a member read only what the Data Provider has released, and change nothing" in {
      Given("a table that the Data Provider has made and not released")
      val w = world
      val created = adminClient.post(s"/api/catalog/v1/$Catalog/namespaces/staging/tables", tableRequest("patients"), adminClient.getAccessToken)
      assert(created.getCode / 100 == 2, s"make the table: HTTP ${created.getCode}")
      assert(tablesIn("released", w.aliceToken).isEmpty)
      And("alice cannot list nor load the tables of the unreleased namespace")
      assert(status("GET", "/namespaces/staging/tables", w.aliceToken) / 100 == 4)
      assert(status("GET", "/namespaces/staging/tables/patients", w.aliceToken) / 100 == 4)

      When("the Data Provider releases it")
      grants.release(Catalog, "patients", "released")

      Then("alice lists and loads the tables of the released namespace")
      assert(tablesIn("released", w.aliceToken) == Seq("patients"))
      assert(status("GET", "/namespaces/released/tables/patients", w.aliceToken) == 200)
      And("bob, who is a member of the project but is not named, still cannot")
      assert(status("GET", "/namespaces/released/tables/patients", w.bobToken) / 100 == 4)
      And("alice cannot make a table in the released namespace, nor drop the one that is there: the project's access is read-only")
      assert(status("POST", "/namespaces/released/tables", w.aliceToken, tableRequest("mine")) / 100 == 4)
      val dropped = {
        val request = HttpRequest.newBuilder(URI.create(s"$polarisUrl/api/catalog/v1/$Catalog/namespaces/released/tables/patients"))
          .header("Authorization", "Bearer " + w.aliceToken).DELETE().build()
        http.send(request, BodyHandlers.ofString()).statusCode()
      }
      assert(dropped / 100 == 4)
      assert(tablesIn("released", w.aliceToken) == Seq("patients"))
    }

    "give a member added later the project's access, and take it from one removed" in {
      Given("alice, a member whom the Data Provider names, and bob, a member of the project whom it does not")
      val w = world

      When("the Data Provider adds bob, and removes alice")
      grants.addMember(Project, w.bob)
      grants.removeMember(Project, w.alice)

      Then("bob reads what the project reads, and alice no longer does")
      assert(status("GET", "/namespaces/released/tables/patients", w.bobToken) == 200)
      assert(status("GET", "/namespaces/released/tables/patients", w.aliceToken) / 100 == 4)
      And("alice's principal is gone, as it had no other role, and bob's is there")
      assert(!adminClient.exists(CatalogConfigurer.principalsURI() + "/" + PolarisProjectGrants.memberPrincipal(w.alice), adminClient.getAccessToken))
      assert(adminClient.exists(CatalogConfigurer.principalsURI() + "/" + PolarisProjectGrants.memberPrincipal(w.bob), adminClient.getAccessToken))
    }

    "make the members those it names, and end the project's access when it is revoked" in {
      Given("a project with bob as its member")
      val w = world

      When("the Data Provider grants it again, naming alice only")
      grants.grant(Project, Catalog, "released", java.util.List.of(w.alice))
      Then("alice reads again, and bob, who is no longer named, does not")
      assert(status("GET", "/namespaces/released/tables/patients", w.aliceToken) == 200)
      assert(status("GET", "/namespaces/released/tables/patients", w.bobToken) / 100 == 4)

      When("the Data Provider revokes the project's access to the catalog")
      grants.revoke(Project, Catalog)
      Then("alice reads nothing, and the project has no role in the Data Provider's Polaris")
      assert(status("GET", "/namespaces/released/tables/patients", w.aliceToken) / 100 == 4)
      assert(!adminClient.exists(CatalogConfigurer.principalRoleURI(PolarisProjectGrants.projectPrincipalRole(Project)), adminClient.getAccessToken))
    }
    "refuse the token of a project that the Data Provider is not the audience of" in {
      Given("a project whose TRE is registered for another Data Provider, and a member of it, whom the Data Provider has granted the project's role")
      val w = world
      val foreign = treProject("cfg-2026-0022", "nw")
      identity.ensureProject(foreign, Map("tre" -> clientSecret()).asJava)
      letTheClientTakeAPassword(foreign)
      val id = member(foreign, "eve@tre.example")
      grants.grant("cfg-2026-0022", Catalog, "released", java.util.List.of(id))

      When("the member calls the Data Provider with their token, which names another Data Provider as its audience")
      val token = tokenOf(foreign, "eve@tre.example")

      Then("it is refused, whatever the Data Provider has granted")
      assert(status("GET", "/namespaces/released", token) == 401)
      And("the same call with a token for this Data Provider is not")
      grants.grant(Project, Catalog, "released", java.util.List.of(w.alice))
      assert(canRead(w.aliceToken))
    }

    "refuse the token of a login that did not give a second factor" in {
      Given("a member of a project whose client does not say that the second factor was given, as a login by password alone would not")
      val project = treProject(OtherProject)
      identity.ensureProject(project, Map("tre" -> clientSecret()).asJava)
      letTheClientTakeAPassword(project, secondFactor = false)
      val id = member(project, "dan@tre.example")
      grants.grant(OtherProject, Catalog, "released", java.util.List.of(id))

      When("the member calls the Data Provider")
      Then("the token is refused")
      assert(status("GET", "/namespaces/released", tokenOf(project, "dan@tre.example")) == 401)
    }

    "let a member of two projects use the access of the project of the token only" in {
      Given("two catalogs, each granted to one project, and a member of both, named by both grants")
      val w = world
      makeCatalog(OtherCatalog)
      grants.revoke(OtherProject, Catalog)
      val second = treProject(OtherProject)
      identity.ensureProject(second, Map("tre" -> clientSecret()).asJava)
      letTheClientTakeAPassword(second)
      val both = member(second, "carol@tre.example")
      identity.addMember(w.project, both)
      grants.grant(OtherProject, OtherCatalog, "released", java.util.List.of(both))
      grants.grant(Project, Catalog, "released", java.util.List.of(both))

      When("the member signs in to each project")
      val ofFirst = tokenOf(w.project, "carol@tre.example")
      val ofSecond = tokenOf(second, "carol@tre.example")

      Then("each token reads the catalog that its project was granted, and the other's not")
      assert(canRead(ofFirst, "released", Catalog))
      assert(!canRead(ofFirst, "released", OtherCatalog))
      assert(canRead(ofSecond, "released", OtherCatalog))
      assert(!canRead(ofSecond, "released", Catalog))
    }
  }
}

@Project
@ProjectIdentity
class ProjectDataAccessAwsSpec extends ProjectDataAccessSpec(AfonCloud.AWS)
