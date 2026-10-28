package health.emrys.afon.acceptance.signing

import health.emrys.afon.acceptance.KindHelper
import health.emrys.afon.acceptance.fixtures.SpecPretifier
import health.emrys.afon.acceptance.tags.Signing
import health.emrys.afon.cdk.components.KyvernoComponent
import health.emrys.afon.cdk.{CdkUtils, DockerImageDefinitions, DockerImageSigner, DockerImages, PublicKeys}
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

import java.nio.file.{Files, Path}
import java.util
import scala.jdk.CollectionConverters._

/**
 * Signs images with the signer of `docker-image --sign` and checks the Kyverno policies of the Kyverno component: that they audit or reject, at
 * admission in a kind cluster, the images that are not signed with the key, and admits those that are.  Needs docker, kind, kubectl, helm
 * and cosign installed, else it is cancelled.
 */
@Signing
class ImageSigningSpec extends SpecPretifier with GivenWhenThen with Eventually {

  private val AuditNamespace = "audit-ns"
  private val EnforceNamespace = "enforce-ns"
  private val EgressNamespace = "egress-ns"
  private val AdmissionTimeout = Span(120, Seconds)

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = AdmissionTimeout, interval = Span(3, Seconds))

  "Images" should {
    "be rejected unless signed with the key of the policy" in {
      val missing = KindHelper.missingTools()
      assume(missing.isEmpty, s"Needs ${missing.asScala.mkString(", ")} installed")

      val kind = new KindHelper()
      try {
        Given("a kind cluster with a registry and Kyverno")
        kind.start()
        kind.installKyverno()

        And("a key that signs, and another key")
        val key = kind.generateKeyPair("key")
        val otherKey = kind.generateKeyPair("other-key")

        And("the same image in the registry as signed, unsigned and signed with the other key")
        val digest = pushCopies(kind, Seq("signed", "unsigned", "other-key", "egress-blocked", "egress-open"))
        sign(kind, "signed", digest, key)
        sign(kind, "other-key", digest, otherKey)
        // images that Kyverno has not verified before, for the egress check, as it may remember those it has
        sign(kind, "egress-blocked", digest, key)
        sign(kind, "egress-open", digest, key)
        And("cosign verifies the signed image with the public key, and not with the other one")
        assert(verify(kind, "signed", digest, key).ok)
        assert(!verify(kind, "signed", digest, otherKey).ok)
        assert(!verify(kind, "unsigned", digest, key).ok)

        // only now, as the registry stops answering the host once it is on the network of the cluster
        kind.connectRegistry()

        When("the policies of the public key are applied, with a namespace audited and another enforced")
        assert(kind.kubectl("create", "namespace", AuditNamespace).ok)
        assert(kind.kubectl("create", "namespace", EnforceNamespace).ok)
        assert(label(kind, AuditNamespace, KyvernoComponent.Mode.AUDIT).ok)
        assert(label(kind, EnforceNamespace, KyvernoComponent.Mode.ENFORCE).ok)
        applyPolicies(kind, publicKeyOf(key))

        Then("the enforced namespace rejects the unsigned image and the one signed with the other key, and admits the signed one")
        eventually {
          val unsigned = admit(kind, EnforceNamespace, "unsigned", digest)
          assert(!unsigned.ok, "unsigned should be rejected in an enforced namespace")
          assert(unsigned.output.contains(KyvernoComponent.ENFORCE_POLICY), unsigned.output)
        }
        val otherKeyed = admit(kind, EnforceNamespace, "other-key", digest)
        assert(!otherKeyed.ok, "an image signed with another key should be rejected")
        assert(otherKeyed.output.contains(KyvernoComponent.ENFORCE_POLICY), otherKeyed.output)
        val signed = admit(kind, EnforceNamespace, "signed", digest)
        assert(signed.ok, signed.output + "\n" + kyvernoLogs(kind))

        And("it checks the init containers too")
        val unsignedInit = admit(kind, EnforceNamespace, "signed", digest, Some("unsigned"))
        assert(!unsignedInit.ok, "a pod with an unsigned init container should be rejected")
        assert(unsignedInit.output.contains(KyvernoComponent.ENFORCE_POLICY), unsignedInit.output)
        assert(admit(kind, EnforceNamespace, "signed", digest, Some("signed")).ok)

        And("the audited namespace admits them all, init containers included")
        for (name <- Seq("signed", "unsigned", "other-key")) {
          assert(admit(kind, AuditNamespace, name, digest).ok, s"$name should be admitted in an audited namespace")
        }
        assert(admit(kind, AuditNamespace, "signed", digest, Some("unsigned")).ok)

        When("the audited namespace is enforced, which is the rollout from Audit to Deny")
        assert(label(kind, AuditNamespace, KyvernoComponent.Mode.ENFORCE).ok)
        Then("it rejects the unsigned image as well")
        eventually {
          val rolledOut = admit(kind, AuditNamespace, "unsigned", digest)
          assert(!rolledOut.ok, "unsigned should be rejected once the namespace is enforced")
          assert(rolledOut.output.contains(KyvernoComponent.ENFORCE_POLICY), rolledOut.output)
        }

        When("the label is removed from the enforced namespace, which is the break-glass")
        assert(kind.kubectl("label", "namespace", EnforceNamespace, s"${KyvernoComponent.NAMESPACE_LABEL}-").ok)
        Then("it admits the unsigned image again")
        eventually {
          val unsigned = admit(kind, EnforceNamespace, "unsigned", digest)
          assert(unsigned.ok, unsigned.output)
        }

        When("the admission controller may only reach DNS and the API server, in a namespace that is enforced")
        assert(kind.kubectl("create", "namespace", EgressNamespace).ok)
        assert(label(kind, EgressNamespace, KyvernoComponent.Mode.ENFORCE).ok)
        kind.apply(CdkUtils.toCanonicalJson(admissionControllerEgress(kind, registry = false)))
        Then("a signed image cannot be verified, so it is rejected: the restriction is enforced by the cluster")
        eventually {
          val blocked = admit(kind, EgressNamespace, "egress-blocked", digest)
          assert(!blocked.ok, "the image should not be verified without access to the registry")
        }

        When("the registry is also allowed, and nothing else: not the KMS, nor a transparency log")
        kind.apply(CdkUtils.toCanonicalJson(admissionControllerEgress(kind, registry = true)))
        Then("a signed image is verified and admitted")
        eventually {
          val open = admit(kind, EgressNamespace, "egress-open", digest)
          assert(open.ok, open.output + "\n" + kyvernoLogs(kind))
        }
      } finally {
        kind.close()
      }
    }
  }

  /**
   * Pushes the curl image to the repositories of the registry, named as given, and returns its digest.
   */
  private def pushCopies(kind: KindHelper, names: Seq[String]): String = {
    KindHelper.runOrFail(null, util.Map.of(), "docker", "pull", DockerImages.CURL_IMAGE)
    names.map { name =>
      val target = s"${kind.hostRegistry}/$name:1"
      KindHelper.runOrFail(null, util.Map.of(), "docker", "tag", DockerImages.CURL_IMAGE, target)
      KindHelper.runOrFail(null, util.Map.of(), "docker", "push", target)
    }
    val inspected = KindHelper.runOrFail(null, util.Map.of(), "docker", "buildx", "imagetools", "inspect",
                                         s"${kind.hostRegistry}/${names.head}:1", "--format", "{{.Manifest.Digest}}")
    assert(inspected.matches("sha256:[0-9a-f]{64}"), inspected)
    inspected
  }

  /**
   * Signs the image the way `docker-image --sign` signs an image of ours.
   */
  private def sign(kind: KindHelper, name: String, digest: String, keyDir: Path): Unit = {
    val image = s"${kind.hostRegistry}/$name"
    val definition = new DockerImageDefinitions.Definition(name, image, "1", digest, "first-party", null)
    val runner: DockerImageSigner.CommandRunner = command => {
      val result = KindHelper.run(null, util.Map.of(), command)
      if (!result.ok) throw new java.io.IOException(result.output)
    }
    val signed = DockerImageSigner.sign(util.Map.of(name, definition), util.List.of(name), kind.hostRegistry,
                                        keyDir.resolve("cosign.key").toString, runner, _ => digest)
    assert(signed.size == 1)
  }

  private def verify(kind: KindHelper, name: String, digest: String, keyDir: Path): KindHelper.Result =
    KindHelper.run(null, util.Map.of(), util.List.of("cosign", "verify", "--key", keyDir.resolve("cosign.pub").toString,
                                                      "--insecure-ignore-tlog=true", s"${kind.hostRegistry}/$name@$digest"))

  /**
   * The log of the admission controller, to see why an image was not admitted.
   */
  private def kyvernoLogs(kind: KindHelper): String =
    kind.kubectl("logs", "-n", "kyverno", "-l", "app.kubernetes.io/component=admission-controller", "--tail=40").output

  /**
   * The public key of the key as the config of a stack holds it, on one line, exported the way `docker-image --public-key` does, and
   * read back the way the Kyverno component does.
   */
  private def publicKeyOf(keyDir: Path): String = {
    val runner: DockerImageSigner.CommandOutput = command => {
      val result = KindHelper.run(null, util.Map.of(), command)
      if (!result.ok) throw new java.io.IOException(result.output)
      result.output
    }
    val property = DockerImageSigner.publicKey(keyDir.resolve("cosign.key").toString, runner)
    assert(!property.matches(".*\\s.*"), "the exported key should be a single word")
    PublicKeys.fromProperty(property)
  }

  /**
   * The NetworkPolicy limiting the egress of the admission controller of Kyverno to DNS and the API server, and the registry if allowed.
   */
  private def admissionControllerEgress(kind: KindHelper, registry: Boolean): util.Map[String, AnyRef] = {
    def tcp(port: Int) = util.Map.of("port", Int.box(port), "protocol", "TCP")
    def to(ip: String, port: Int): util.Map[String, AnyRef] =
      util.Map.of("to", util.List.of(util.Map.of("ipBlock", util.Map.of("cidr", s"$ip/32"))), "ports", util.List.of(tcp(port)))
    val dns: util.Map[String, AnyRef] = util.Map.of(
      "to", util.List.of(util.Map.of(
        "namespaceSelector", util.Map.of("matchLabels", util.Map.of("kubernetes.io/metadata.name", "kube-system")),
        "podSelector", util.Map.of("matchLabels", util.Map.of("k8s-app", "kube-dns")))),
      "ports", util.List.of(util.Map.of("port", Int.box(53), "protocol", "UDP"), tcp(53)))
    val rules = new util.ArrayList[util.Map[String, AnyRef]]()
    rules.add(dns)
    rules.add(to(kind.apiServerIp, 6443))
    if (registry) rules.add(to(kind.registryIp, KindHelper.REGISTRY_PORT))
    util.Map.of("apiVersion", "networking.k8s.io/v1", "kind", "NetworkPolicy",
                "metadata", util.Map.of("name", "kyverno-admission-egress", "namespace", "kyverno"),
                "spec", util.Map.of(
                  "podSelector", util.Map.of("matchLabels", util.Map.of("app.kubernetes.io/component", "admission-controller")),
                  "policyTypes", util.List.of("Egress"),
                  "egress", rules))
  }

  private def label(kind: KindHelper, namespace: String, mode: KyvernoComponent.Mode): KindHelper.Result =
    kind.kubectl("label", "namespace", namespace, s"${KyvernoComponent.NAMESPACE_LABEL}=${mode.label}", "--overwrite")

  private def applyPolicies(kind: KindHelper, publicKey: String): Unit = {
    for (mode <- KyvernoComponent.Mode.values()) {
      kind.apply(CdkUtils.toCanonicalJson(KyvernoComponent.imagePolicy(publicKey, mode)))
    }
    for (mode <- KyvernoComponent.Mode.values()) {
      eventually {
        val ready = kind.kubectl("get", s"imagevalidatingpolicies.policies.kyverno.io/${mode.policy}", "-o",
                                 "jsonpath={.status.conditionStatus.ready}")
        assert(ready.output == "true", s"The policy ${mode.policy} is not ready: ${ready.output}")
      }
    }
  }

  /**
   * Has the cluster admit a pod of the image, and of another as its init container if given, in a dry run, which runs the admission
   * webhooks and creates nothing.
   */
  private def admit(kind: KindHelper, namespace: String, name: String, digest: String, init: Option[String] = None): KindHelper.Result = {
    def image(of: String) = s"${kind.clusterRegistry}/$of@$digest"
    val spec = new util.HashMap[String, AnyRef]()
    spec.put("restartPolicy", "Never")
    spec.put("containers", util.List.of(util.Map.of("name", "main", "image", image(name))))
    init.foreach(of => spec.put("initContainers", util.List.of(util.Map.of("name", "init", "image", image(of)))))
    kind.admit(CdkUtils.toCanonicalJson(util.Map.of("apiVersion", "v1", "kind", "Pod",
                                                    "metadata", util.Map.of("name", s"pod-$name", "namespace", namespace),
                                                    "spec", spec)))
  }
}
