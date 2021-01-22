package sttp.tapir.server.finatra

import com.twitter.finagle.http.{Method, Request, Response, Status}
import com.twitter.util.Future
import com.twitter.util.logging.Logging
import sttp.capabilities.Effect
import sttp.monad.MonadError
import sttp.tapir.{DecodeResult, Endpoint, EndpointIO, EndpointInput}
import sttp.tapir.internal._
import sttp.tapir.EndpointInput.{FixedMethod, PathCapture}
import sttp.tapir.server.{DecodeFailureContext, DecodeFailureHandling, ServerDefaults, ServerEndpoint}
import sttp.tapir.server.internal.{DecodeInputs, DecodeInputsResult, InputValues, InputValuesResult}

import java.nio.charset.Charset
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait FinatraServerInterpreter extends Logging {
  def toRoute[I, E, O](e: Endpoint[I, E, O, Effect[Future]])(logic: I => Future[Either[E, O]])(implicit
      serverOptions: FinatraServerOptions
  ): FinatraRoute =
    toRoute(e.serverLogic(logic))

  def toRouteRecoverErrors[I, E, O](e: Endpoint[I, E, O, Effect[Future]])(logic: I => Future[O])(implicit
      eIsThrowable: E <:< Throwable,
      eClassTag: ClassTag[E]
  ): FinatraRoute =
    toRoute(e.serverLogicRecoverErrors(logic))

  def toRoute[I, E, O](e: ServerEndpoint[I, E, O, Effect[Future], Future])(implicit serverOptions: FinatraServerOptions): FinatraRoute = {
    val handler = { request: Request =>
      def decodeBody(result: DecodeInputsResult): Future[DecodeInputsResult] = {
        result match {
          case values: DecodeInputsResult.Values =>
            values.bodyInput match {
              case Some(bodyInput @ EndpointIO.Body(bodyType, codec, _)) =>
                new FinatraRequestToRawBody(serverOptions)
                  .apply(bodyType, request.content, request.charset.map(Charset.forName), request)
                  .map { rawBody =>
                    val decodeResult = codec.decode(rawBody)
                    decodeResult match {
                      case DecodeResult.Value(bodyV)     => values.setBodyInputValue(bodyV)
                      case failure: DecodeResult.Failure => DecodeInputsResult.Failure(bodyInput, failure): DecodeInputsResult
                    }
                  }
              case None => Future.value(values)
            }
          case failure: DecodeInputsResult.Failure => Future.value(failure)
        }
      }

      def valueToResponse(value: Any): Future[Response] = {
        val i = value.asInstanceOf[I]

        e.logic(FutureMonadError)(i)
          .flatMap {
            case Right(result) => OutputToFinatraResponse(Status(ServerDefaults.StatusCodes.success.code), e.output, result)
            case Left(err)     => OutputToFinatraResponse(Status(ServerDefaults.StatusCodes.error.code), e.errorOutput, err)
          }
          .map { result =>
            serverOptions.logRequestHandling.requestHandled(e.endpoint, result.statusCode)
            result
          }
          .onFailure { case NonFatal(ex) =>
            serverOptions.logRequestHandling.logicException(e.endpoint, ex)
            error(ex)
          }
      }

      def handleDecodeFailure(
          e: Endpoint[_, _, _, _],
          input: EndpointInput[_, _],
          failure: DecodeResult.Failure
      ): Future[Response] = {
        val decodeFailureCtx = DecodeFailureContext(input, failure, e)
        val handling = serverOptions.decodeFailureHandler(decodeFailureCtx)

        handling match {
          case DecodeFailureHandling.NoMatch =>
            serverOptions.logRequestHandling.decodeFailureNotHandled(e, decodeFailureCtx)
            Future.value(Response(Status.BadRequest))
          case DecodeFailureHandling.RespondWithResponse(output, value) =>
            serverOptions.logRequestHandling.decodeFailureHandled(e, decodeFailureCtx, value)
            OutputToFinatraResponse(Status(ServerDefaults.StatusCodes.error.code), output, value)
        }
      }

      decodeBody(DecodeInputs(e.input, new FinatraDecodeInputsContext(request))).flatMap {
        case values: DecodeInputsResult.Values =>
          InputValues(e.input, values)(FutureMonadError).flatMap {
            case InputValuesResult.Value(params, _)        => valueToResponse(params.asAny)
            case InputValuesResult.Failure(input, failure) => handleDecodeFailure(e.endpoint, input, failure)
          }
        case DecodeInputsResult.Failure(input, failure) => handleDecodeFailure(e.endpoint, input, failure)
      }
    }

    FinatraRoute(handler, httpMethod(e.endpoint), path(e.input))
  }

  private[finatra] def path(input: EndpointInput[_, _]): String = {
    val p = input
      .asVectorOfBasicInputs()
      .collect {
        case segment: EndpointInput.FixedPath[_] => segment.show
        case PathCapture(Some(name), _, _)       => s"/:$name"
        case PathCapture(_, _, _)                => "/:param"
        case EndpointInput.PathsCapture(_, _)    => "/:*"
      }
      .mkString
    if (p.isEmpty) "/:*" else p
  }

  private[finatra] def httpMethod(endpoint: Endpoint[_, _, _, _]): Method = {
    endpoint.input
      .asVectorOfBasicInputs()
      .collectFirst { case FixedMethod(m, _, _) =>
        Method(m.method)
      }
      .getOrElse(Method("ANY"))
  }
}

object FinatraServerInterpreter extends FinatraServerInterpreter

private[finatra] object FutureMonadError extends MonadError[Future] {
  override def unit[T](t: T): Future[T] = Future(t)
  override def map[T, T2](fa: Future[T])(f: (T) => T2): Future[T2] = fa.map(f)
  override def flatMap[T, T2](fa: Future[T])(f: (T) => Future[T2]): Future[T2] = fa.flatMap(f)
  override def error[T](t: Throwable): Future[T] = Future.exception(t)
  override protected def handleWrappedError[T](rt: Future[T])(h: PartialFunction[Throwable, Future[T]]): Future[T] = rt.rescue(h)
  override def ensure[T](f: Future[T], e: => Future[Unit]): Future[T] = f.ensure(e.toJavaFuture.get())
}
