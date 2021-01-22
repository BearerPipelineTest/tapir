package sttp.tapir.server.finatra.cats

import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO, Resource, Timer}
import sttp.capabilities.Effect
import sttp.tapir.Endpoint
import sttp.tapir.server.finatra.{FinatraRoute, FinatraServerOptions, FinatraTestServerInterpreter}
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.server.{DecodeFailureHandler, ServerDefaults, ServerEndpoint}
import sttp.tapir.tests.Port

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class FinatraCatsTestServerInterpreter extends TestServerInterpreter[IO, Effect[IO], FinatraRoute] {
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)

  override def route[I, E, O](
      e: ServerEndpoint[I, E, O, Effect[IO], IO],
      decodeFailureHandler: Option[DecodeFailureHandler] = None
  ): FinatraRoute = {
    implicit val serverOptions: FinatraServerOptions =
      FinatraServerOptions.default.copy(decodeFailureHandler = decodeFailureHandler.getOrElse(ServerDefaults.decodeFailureHandler))
    FinatraCatsServerInterpreter.toRoute(e)
  }

  override def routeRecoverErrors[I, E <: Throwable, O](e: Endpoint[I, E, O, Effect[IO]], fn: I => IO[O])(implicit
      eClassTag: ClassTag[E]
  ): FinatraRoute = FinatraCatsServerInterpreter.toRouteRecoverErrors(e)(fn)

  override def server(routes: NonEmptyList[FinatraRoute]): Resource[IO, Port] = FinatraTestServerInterpreter.server(routes)
}
