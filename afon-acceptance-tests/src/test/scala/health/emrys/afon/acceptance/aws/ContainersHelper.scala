package health.emrys.afon.acceptance.aws

import org.testcontainers.containers.GenericContainer

object ContainersHelper {

  val myContainer = new GenericContainer("apache/polaris")
    .withExposedPorts(8080, 9090)
//    .withEnv("MY_ENV", "value");

}
