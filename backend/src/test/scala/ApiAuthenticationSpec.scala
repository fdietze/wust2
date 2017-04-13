package wust.backend.auth

import org.scalatest._
import scala.concurrent.Future

import wust.api.{Authentication, User}

class ApiAuthenticationSpec extends AsyncFreeSpec with MustMatchers {

  def validAuth(user: User) = Authentication(user, Long.MaxValue, "abc")
  def expiredAuth(user: User) = Authentication(user, 123L, "abc")
  class ErrorEx extends Exception("meh")

  def ApiAuth(auth: Option[Authentication], createImplicitAuth: () => Option[Authentication], toError: => Exception = new ErrorEx) =
    new ApiAuthentication(Future.successful(auth), () => Future.successful(createImplicitAuth()), toError)

  "no user, no implicit" - {
    val api = ApiAuth(None, () => None)

    "actualAuth" in api.actualAuth.map { auth =>
      auth mustEqual None
    }

    "createOrActualAuth" in api.createdOrActualAuth.map { auth =>
      auth mustEqual None
    }

    "actualOrImplicitAuth" in {
      api.actualOrImplicitAuth.map { auth =>
        auth mustEqual None
      }

      api.createdOrActualAuth.map { auth =>
        auth mustEqual None
      }
    }

    "withUserOpt" in api.withUserOpt { user =>
      user mustEqual None
    }

    "withUser" in recoverToSucceededIf[ErrorEx] {
      api.withUser(u => Future.successful(u))
    }

    "withUser 2" in recoverToSucceededIf[ErrorEx] {
      api.withUser(Future.successful(1))
    }

    "withUserOrImplicit" in recoverToSucceededIf[ErrorEx] {
      api.withUserOrImplicit(u => Future.successful(u))
    }

    "withUserOrImplicit 2" in recoverToSucceededIf[ErrorEx] {
      api.withUserOrImplicit(Future.successful(1))
    }
  }

  "no user, with implicit" - {
    val anonUser = User("anon")
    val anonAuth = validAuth(anonUser)
    val api = ApiAuth(None, () => Option(anonAuth))

    "actualAuth" in api.actualAuth.map { auth =>
      auth mustEqual None
    }

    "createOrActualAuth" in api.createdOrActualAuth.map { auth =>
      auth mustEqual None
    }

    "actualOrImplicitAuth" in {
      for {
        auth <- api.actualOrImplicitAuth
        created <- api.createdOrActualAuth
      } yield {
        auth mustEqual created
        auth mustEqual Option(anonAuth)
      }
    }

    "withUserOpt" in api.withUserOpt { user =>
      user mustEqual None
    }

    "withUser" in recoverToSucceededIf[ErrorEx] {
      api.withUser(u => Future.successful(u))
    }

    "withUser 2" in recoverToSucceededIf[ErrorEx] {
      api.withUser(Future.successful(1))
    }

    "withUserOrImplicit" in api.withUserOrImplicit { user =>
      user mustEqual anonUser
    }

    "withUserOrImplicit 2" in api.withUserOrImplicit {
      Future.successful(1 mustEqual 1)
    }
  }

  "no user, with expired implicit" - {
    val anonUser = User("anon")
    val anonAuth = expiredAuth(anonUser)
    val api = ApiAuth(None, () => Option(anonAuth))

    "actualAuth" in api.actualAuth.map { auth =>
      auth mustEqual None
    }

    "createOrActualAuth" in api.createdOrActualAuth.map { auth =>
      auth mustEqual None
    }

    "actualOrImplicitAuth" in {
      api.actualOrImplicitAuth.map { auth =>
        auth mustEqual None
      }

      api.createdOrActualAuth.map { auth =>
        auth mustEqual None
      }
    }

    "withUserOpt" in api.withUserOpt { user =>
      user mustEqual None
    }

    "withUser" in recoverToSucceededIf[ErrorEx] {
      api.withUser(u => Future.successful(u))
    }

    "withUser 2" in recoverToSucceededIf[ErrorEx] {
      api.withUser(Future.successful(1))
    }

    "withUserOrImplicit" in recoverToSucceededIf[ErrorEx] {
      api.withUserOrImplicit(u => Future.successful(u))
    }

    "withUserOrImplicit 2" in recoverToSucceededIf[ErrorEx] {
      api.withUserOrImplicit(Future.successful(1))
    }
  }

  "with user" - {
    val daUser = User("harals")
    val daAuth = validAuth(daUser)
    val api = ApiAuth(Option(daAuth), () => Option(daAuth))

    "actualAuth" in api.actualAuth.map { auth =>
      auth mustEqual Option(daAuth)
    }

    "createOrActualAuth" in api.createdOrActualAuth.map { auth =>
      auth mustEqual Option(daAuth)
    }

    "actualOrImplicitAuth" in {
      for {
        auth <- api.actualOrImplicitAuth
        created <- api.createdOrActualAuth
      } yield {
        auth mustEqual created
        auth mustEqual Option(daAuth)
      }
    }

    "withUserOpt" in api.withUserOpt { user =>
      user mustEqual Option(daUser)
    }

    "withUser" in api.withUser { user =>
      user mustEqual daUser
    }

    "withUser 2" in api.withUser {
      Future.successful(1 mustEqual 1)
    }

    "withUserOrImplicit" in api.withUserOrImplicit { user =>
      user mustEqual daUser
    }

    "withUserOrImplicit 2" in api.withUserOrImplicit {
      Future.successful(1 mustEqual 1)
    }
  }

  "with expired user" - {
    val daUser = User("harals")
    val daAuth = expiredAuth(daUser)
    val api = ApiAuth(Option(daAuth), () => Option(daAuth))

    "actualAuth" in api.actualAuth.map { auth =>
      auth mustEqual None
    }

    "createOrActualAuth" in api.createdOrActualAuth.map { auth =>
      auth mustEqual None
    }

    "actualOrImplicitAuth" in {
      api.actualOrImplicitAuth.map { auth =>
        auth mustEqual None
      }

      api.createdOrActualAuth.map { auth =>
        auth mustEqual None
      }
    }

    "withUserOpt" in api.withUserOpt { user =>
      user mustEqual None
    }

    "withUser" in recoverToSucceededIf[ErrorEx] {
      api.withUser(u => Future.successful(u))
    }

    "withUser 2" in recoverToSucceededIf[ErrorEx] {
      api.withUser(Future.successful(1))
    }

    "withUserOrImplicit" in recoverToSucceededIf[ErrorEx] {
      api.withUserOrImplicit(u => Future.successful(u))
    }

    "withUserOrImplicit 2" in recoverToSucceededIf[ErrorEx] {
      api.withUserOrImplicit(Future.successful(1))
    }
  }
}
