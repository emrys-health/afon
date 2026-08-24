package health.emrys.afon.acceptance.permissions

import health.emrys.afon.config.{AfonUser, AfonUserAcl, AfonUserProvider}

import java.util

class TestUsers {
  val UserName      = "userX"
  val UserPassword  = "password"
  val User          = new AfonUser(UserName, UserPassword, "email@test.com", "slug", "first", "second")
  val UserAcl       = new AfonUserAcl(UserName, "all")

  val userProvider = new AfonUserProvider() {
    override def getUsers: util.List[AfonUser] = util.List.of(User)
    override def getUserNames: util.List[String] = util.List.of(User.username())
    override def getUser(UserName: String): AfonUser = User;
    override def getUserAcls: util.List[AfonUserAcl] = util.List.of(UserAcl)
    override def isAuthorised(username: String, catalogName: String): Boolean = true
  }
}
