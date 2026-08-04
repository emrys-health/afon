package health.emrys.afon.acceptance.permissions

import health.emrys.afon.acceptance.AcceptanceTestConfig
import health.emrys.afon.config.{AfonUser, AfonUserProvider}

import java.util

class TestUsers {
  val UserName      = "userX"
  val UserPassword  = "password"
  val User          = new AfonUser(UserName, UserPassword, "email@test.com", "slug", "first", "second")

  val userProvider = new AfonUserProvider() {
    override def getUsers: util.List[AfonUser] = util.List.of(User)
  }
}
