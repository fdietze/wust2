package wust.backend

import org.scalatest._

import scala.concurrent.Future
import scala.collection.mutable

class StateHolderSpec extends AsyncFreeSpec with MustMatchers {
  private def newStateHolder[T](state: T) = {
    new StateHolder[T, Int](Future.successful(state))
  }

  "result to requestResponse" in {
    val initialState = ""
    val holder = newStateHolder(initialState)
    import holder.resultIsRequestResponse

    val res: RequestResponse[Int, Int] = 2

    holder.state.map(_ mustEqual initialState)
    holder.events.map(_.size mustEqual 0)
    res.result mustEqual 2
    res.events.size mustEqual 0
  }

  "future result to requestResponse" in {
    val initialState = ""
    val holder = newStateHolder(initialState)
    import holder.futureResultIsRequestResponse

    val res: Future[RequestResponse[Int, Int]] = Future.successful[Int](2)

    holder.state.map(_ mustEqual initialState)
    holder.events.map(_.size mustEqual 0)
    res.map { res =>
      res.result mustEqual 2
      res.events.size mustEqual 0
    }
  }

  "execute result function" in {
    val initialState = ""
    val holder = newStateHolder(initialState)
    import holder.resultFunctionIsExecuted

    val res: Future[Int] = { (state: String) =>
      Future.successful(2)
    }

    holder.state.map(_ mustEqual initialState)
    holder.events.map(_.size mustEqual 0)
    res.map { res =>
      res mustEqual 2
    }
  }

  "execute requestResponse function" in {
    val initialState = ""
    val holder = newStateHolder(initialState)
    import holder.{ respondWithEvents, responseFunctionIsExecuted }

    val res: Future[Int] = { (state: String) =>
      Future.successful(respondWithEvents(2, 666))
    }

    holder.state.map(_ mustEqual initialState)
    holder.events.map(_ must contain theSameElementsAs Set(666))
    res.map { res =>
      res mustEqual 2
    }
  }

  "execute stateEffect function" in {
    val initialState = ""
    val holder = newStateHolder(initialState)
    import holder.{ respondWithEvents, effectFunctionIsExecuted }

    val nextState = "new"
    val res: Future[Int] = { (state: String) =>
      val newState = Future.successful(nextState)
      val response = Future.successful(respondWithEvents(2, 666))
      StateEffect(newState, response)
    }

    holder.state.map(_ mustEqual nextState)
    holder.events.map(_ must contain theSameElementsAs Set(666))
    res.map { res =>
      res mustEqual 2
    }
  }

  "chain stateholder state/events" in {
    val initialState = ""
    val holder = newStateHolder(initialState)
    import holder._

    val nextState = "new"
    val res: Future[String] = { (state: String) =>
      val newState = Future.successful(nextState)
      val response = Future.successful(respondWithEvents("response2", 777))
      StateEffect(newState, response)
    }

    holder.state.map(_ mustEqual nextState)
    holder.events.map(_ must contain theSameElementsAs Set(777))
    res.map { res =>
      res mustEqual "response2"
    }

    val betweenState = holder.state

    {
      val holder = newStateHolder(betweenState)
      val nextState = "new2"
      val res: Future[String] = { (state: String) =>
        val newState = Future.successful(nextState)
        val response = Future.successful(respondWithEvents("response", 666))
        StateEffect(newState, response)
      }

      holder.state.map(_ mustEqual nextState)
      holder.events.map(_ must contain theSameElementsAs Set(666))
      res.map { res =>
        res mustEqual "response"
      }
    }
  }

  "RequestResponse" - {
    val initialState = ""
    val holder = newStateHolder(initialState)
    import holder.respondWithEventsIf

    "respondWithEventsIf true" in {
      val response = respondWithEventsIf(true, 666)

      response.result mustEqual true
      holder.events.map(_.size mustEqual 0)
    }

    "respondWithEventsIf false" in {
      val response = respondWithEventsIf(false, 666)

      response.result mustEqual false
      holder.events.map(_.size mustEqual 0)
    }
  }
}
