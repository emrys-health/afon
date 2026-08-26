package health.emrys.afon.acceptance.permissions

import health.emrys.afon.config.{AfonUser, AfonUserAcl, AfonUserProvider}

import java.util

object TestUsers {
  val UserName      = "userX"
  val UserPassword  = "password"
  val User          = new AfonUser(UserName, UserPassword, "email@test.com", "slug", "first", "second")
  val UserAcl       = new AfonUserAcl(UserName, "all")

  def apply():AfonUserProvider = apply(User)

  def apply(user: AfonUser):AfonUserProvider = new AfonUserProvider() {
    override def getUsers: util.List[AfonUser] = util.List.of(user)
    override def getUserNames: util.List[String] = util.List.of(user.username())
    override def getUser(UserName: String): AfonUser = user;
    override def getUserAcls: util.List[AfonUserAcl] = util.List.of(UserAcl)
    override def isAuthorised(username: String, catalogName: String): Boolean = true
  }
}
