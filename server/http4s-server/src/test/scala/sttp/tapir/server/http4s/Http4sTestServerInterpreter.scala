package sttp.tapir.server.http4s

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.syntax.all._
import org.http4s.syntax.kleisli._
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.server.blaze.BlazeServerBuilder
import sttp.capabilities.{Effect, WebSockets}
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.Endpoint
import sttp.tapir.server.{DecodeFailureHandler, ServerDefaults, ServerEndpoint}
import sttp.tapir.server.tests.TestServerInterpreter
import sttp.tapir.tests.Port

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class Http4sTestServerInterpreter extends TestServerInterpreter[IO, Fs2Streams[IO] with WebSockets with Effect[IO], HttpRoutes[IO]] {
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)

  override def route[I, E, O](
      e: ServerEndpoint[I, E, O, Fs2Streams[IO] with WebSockets with Effect[IO], IO],
      decodeFailureHandler: Option[DecodeFailureHandler] = None
  ): HttpRoutes[IO] = {
    implicit val serverOptions: Http4sServerOptions[IO] = Http4sServerOptions
      .default[IO]
      .copy(
        decodeFailureHandler = decodeFailureHandler.getOrElse(ServerDefaults.decodeFailureHandler)
      )
    Http4sServerInterpreter.toRoutes(e)
  }

  override def routeRecoverErrors[I, E <: Throwable, O](
      e: Endpoint[I, E, O, Fs2Streams[IO] with WebSockets with Effect[IO]],
      fn: I => IO[O]
  )(implicit
      eClassTag: ClassTag[E]
  ): HttpRoutes[IO] = {
    Http4sServerInterpreter.toRouteRecoverErrors(e)(fn)
  }

  override def server(routes: NonEmptyList[HttpRoutes[IO]]): Resource[IO, Port] = {
    val service: Kleisli[IO, Request[IO], Response[IO]] = routes.reduceK.orNotFound

    BlazeServerBuilder[IO](ExecutionContext.global)
      .bindHttp(0, "localhost")
      .withHttpApp(service)
      .resource
      .map(_.address.getPort)
  }
}
