package health.emrys.afon.acceptance.project

import com.fasterxml.jackson.databind.ObjectMapper
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.config.{AfonCloud, ConfigUtils}
import health.emrys.afon.project.{ProjectStack, FileProjectStackProvider, ProjectStackEnv, ProjectStackHome, ProjectStackNames}
import org.scalatest.GivenWhenThen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * What a project asks of a cloud, with the real tools (the aws, az and gcloud CLIs) against the real cloud of the config that this runs in, which the
 * cloud's specs are run with: needs the CLI logged in, with the permissions to write the object storage, and nothing else.  Nothing here changes
 * what already is in the cloud: every name has a random id, and what is made is deleted again, whether the test passes or not.
 *
 * - The state of a project's stacks is stored by {@code cdktf-store} under the project's id, found from the project's own cdktf dir, with the storage
 *   account read from the config of the project: pushed, listed, restored and deleted.  The scripts run as the launcher runs them, in the home of a
 *   project, which is what a project's stages do.
 * - The names that {@code configure} gives a project's resources are names that the cloud accepts: the storage account and key vault of Azure are
 *   checked with the cloud's own check of a name, which makes nothing, and the buckets of AWS and GCP are made, and deleted.
 *
 * No cloud is special: the specs of the clouds differ only in the tools they run, which are those of the subclasses' cloud.
 */
abstract class ProjectCloudSpec(cloud: AfonCloud) extends SpecPretifier with GivenWhenThen {

  private val mapper = new ObjectMapper()
  private val lower = cloud.toString.toLowerCase
  private val stack = s"$lower-full"
  /** The repository, which has the scripts and the launcher, as the distribution of the CLI does. */
  private val repository = Path.of("..").toAbsolutePath.normalize()
  private val launcher = repository.resolve("afon-cli/src/main/scripts/afon-cli-scripts.sh")
  /** The config that this runs in, as the access specs are: the CI's. */
  private lazy val realConfig = Path.of(ConfigUtils.configDir())

  private def newProjectId(): String = "acc-" + UUID.randomUUID().toString.take(8)

  private def exec(command: Seq[String], env: Map[String, String] = Map.empty): (Int, String) = {
    val builder = new ProcessBuilder(command.asJava).redirectErrorStream(true)
    env.foreach { case (k, v) => builder.environment().put(k, v) }
    val process = builder.start()
    val output = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    (process.waitFor(), output)
  }

  /** A project of the cloud, made as the example is, with the id given. */
  private def projectOf(id: String): ProjectStack =
    FileProjectStackProvider.parse(
      s"""project-stack: {id: $id, cloud: $lower}
         |identity: {issuerUrl: 'https://id.example.org/realms/polaris', acr: mfa}
         |dataProviders:
         |  defined:
         |    - {id: p, catalogUri: 'https://polaris.example/api', catalogName: $id, audience: provider-p, bucket: b}
         |members: [ada@example.org]
         |stacks:
         |  create: {auditBucket: $id-audit}
         |""".stripMargin)

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root)) {
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => { Files.deleteIfExists(p); () }) finally paths.close()
    }

  "The state of a project" should {

    "be stored, listed, restored and deleted in the object storage of the cloud, under the project's id" in {
      val id = newProjectId()
      val root = Files.createTempDirectory("afon-project-store")
      val tag = "current"
      var cdktf: Path = null
      var home: Path = null
      /** The scripts, as a project's stages run them: in its home, with the cloud of the project. */
      def store(args: String*): (Int, String) =
        exec(Seq(launcher.toString, "--home", home.toString, "--cdktf", cdktf.toString, "cdktf/store.sh") ++ args,
             Map("AFON_CLI_DIR" -> repository.toString, "AFON_CLOUD" -> lower))
      try {
        Given("the config of a project, made from the config this runs in, and the state of one of its stacks")
        val config = Files.createDirectories(root.resolve(s"config-$id"))
        for (name <- Seq("afon.properties", s"afon-$lower.properties", "catalogs.properties", s"catalogs-$lower.properties")) {
          val file = realConfig.resolve(name)
          if (Files.isRegularFile(file)) Files.copy(file, config.resolve(name))
        }
        cdktf = Files.createDirectories(root.resolve(s"cdktf-$id"))
        val state = cdktf.resolve(s"terraform.$stack.tfstate")
        val original = """{"version":4,"serial":1,"lineage":"acceptance","outputs":{},"resources":[]}"""
        Files.writeString(state, original)
        Files.writeString(Files.createDirectories(cdktf.resolve(s"stacks/$stack")).resolve("cdk.tf.json"), "{}")
        home = ProjectStackHome.prepare(root, projectOf(id))

        When("it is stored")
        val pushed = store("push", tag, stack, "--force")
        Then("the state is in the store, under the id of the project")
        assert(pushed._1 == 0, pushed._2)
        assert(pushed._2.contains(s"$id/$tag"), pushed._2)
        val listed = store("list")
        assert(listed._1 == 0, listed._2)
        assert(listed._2.contains(s"Snapshots of env $id") && listed._2.contains(tag) && listed._2.contains(stack), listed._2)

        When("the state here differs from what is stored")
        Files.writeString(state, """{"version":4,"serial":2,"lineage":"acceptance","outputs":{},"resources":[]}""")
        Then("it is not overwritten without --force")
        val refused = store("pull", tag, stack)
        assert(refused._1 != 0, refused._2)
        assert(Files.readString(state).contains("\"serial\":2"))
        And("it is restored with --force")
        val pulled = store("pull", tag, stack, "--force")
        assert(pulled._1 == 0, pulled._2)
        assert(Files.readString(state) == original)

        When("it is deleted")
        val deleted = store("delete", tag, stack)
        Then("nothing of the project is left in the store")
        assert(deleted._1 == 0, deleted._2)
        val after = store("list")
        assert(!after._2.contains(stack), after._2)
      } finally {
        // whatever happened, nothing of this is left in the store
        if (home != null) store("delete", tag, stack)
        deleteTree(root)
      }
    }
  }

  "The names of a project's resources" should {

    "be accepted by the cloud" in {
      val id = newProjectId()
      val project = projectOf(id)
      val env = ProjectStackEnv.envProperties(realConfig)
      val names = ProjectStackNames.names(project, env).asScala.toMap
      Given(s"the names that configure gives project $id from the config this runs in")
      info(names.map { case (k, v) => s"$k=$v" }.mkString(", "))
      val made = ListBuffer.empty[() => Unit]
      try {
        cloud match {
          case AfonCloud.AZURE =>
            val account = names("AZURE_STORAGE_ACCOUNT")
            val vault = names("AZURE_KEY_VAULT_NAME")
            val subscription = env.getProperty("AZURE_SUBSCRIPTION", "").trim
            Then("the cloud's check of the name of the storage account says it is a name, and free")
            val storage = exec(Seq("az", "storage", "account", "check-name", "--name", account, "--output", "json"))
            assert(storage._1 == 0, storage._2)
            val storageAnswer = mapper.readTree(storage._2)
            assert(storageAnswer.path("nameAvailable").asBoolean, s"$account: ${storage._2}")
            And("so does its check of the name of the key vault")
            val keyVault = exec(Seq("az", "rest", "--method", "post", "--output", "json", "--url",
                                    s"https://management.azure.com/subscriptions/$subscription/providers/Microsoft.KeyVault/checkNameAvailability?api-version=2023-07-01",
                                    "--body", s"""{"name":"$vault","type":"Microsoft.KeyVault/vaults"}"""))
            assert(keyVault._1 == 0, keyVault._2)
            assert(mapper.readTree(keyVault._2).path("nameAvailable").asBoolean, s"$vault: ${keyVault._2}")
          case AfonCloud.AWS =>
            val region = env.getProperty("AWS_REGION", "").trim
            val buckets = names.filter(_._1.contains("BUCKET")).values.toSeq.distinct
            assert(buckets.nonEmpty, "the config has no catalog, so no bucket of the project is named")
            Then("the cloud makes a bucket of each name")
            for (bucket <- buckets) {
              val created = exec(Seq("aws", "s3api", "create-bucket", "--bucket", bucket, "--region", region,
                                     "--create-bucket-configuration", s"LocationConstraint=$region"))
              assert(created._1 == 0, s"$bucket: ${created._2}")
              made += (() => { exec(Seq("aws", "s3api", "delete-bucket", "--bucket", bucket, "--region", region)); () })
            }
          case AfonCloud.GCP =>
            val project = env.getProperty("GCP_PROJECT_ID", "").trim
            val region = env.getProperty("GCP_REGION", "").trim
            val buckets = names.filter(_._1.contains("BUCKET")).values.toSeq.distinct
            assert(buckets.nonEmpty, "the config has no catalog, so no bucket of the project is named")
            Then("the cloud makes a bucket of each name")
            for (bucket <- buckets) {
              val created = exec(Seq("gcloud", "storage", "buckets", "create", s"gs://$bucket", s"--location=$region", s"--project=$project"))
              assert(created._1 == 0, s"$bucket: ${created._2}")
              made += (() => { exec(Seq("gcloud", "storage", "buckets", "delete", s"gs://$bucket")); () })
            }
        }
      } finally {
        // what was made is deleted, so that nothing of the test is left in the cloud
        made.foreach(_.apply())
      }
    }
  }
}
