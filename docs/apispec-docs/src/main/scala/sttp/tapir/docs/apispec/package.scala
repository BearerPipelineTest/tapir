package sttp.tapir.docs

import sttp.tapir.{Codec, Endpoint, EndpointInput, SchemaType}
import sttp.tapir.apispec.{ExampleMultipleValue, ExampleSingleValue, ExampleValue, SecurityScheme}

package object apispec {
  private[docs] type SchemeName = String
  private[docs] type SecuritySchemes = Map[EndpointInput.Auth[_, _], (SchemeName, SecurityScheme)]

  private[docs] def uniqueName(base: String, isUnique: String => Boolean): String = {
    var i = 0
    var result = base
    while (!isUnique(result)) {
      i += 1
      result = base + i
    }
    result
  }

  private[docs] def rawToString[T](v: Any): String = v.toString
  private[docs] def encodeToString[T](codec: Codec[_, T, _]): T => Option[String] = e => Some(rawToString(codec.encode(e)))

  private[docs] def exampleValue[T](v: String): ExampleValue = ExampleSingleValue(v)
  private[docs] def exampleValue[T](codec: Codec[_, T, _], e: T): Option[ExampleValue] = {
    (codec.encode(e), codec.schema.schemaType) match {
      case (it: Iterable[_], _: SchemaType.SArray) => Some(ExampleMultipleValue(it.map(rawToString).toList))
      case (it: Iterable[_], _)                    => it.headOption.map(v => ExampleSingleValue(rawToString(v)))
      case (it: Option[_], _)                      => it.map(v => ExampleSingleValue(rawToString(v)))
      case (v, _)                                  => Some(ExampleSingleValue(rawToString(v)))
    }
  }

  private[docs] def nameAllPathCapturesInEndpoint(e: Endpoint[_, _, _, _]): Endpoint[_, _, _, _] = {
    val (input2, _) = new EndpointInputMapper[Int](
      { case (EndpointInput.PathCapture(None, codec, info), i) =>
        (EndpointInput.PathCapture(Some(s"p$i"), codec, info), i + 1)
      },
      PartialFunction.empty
    ).mapInput(e.input, 1)

    e.copy(input = input2)
  }

  private[docs] def namedPathComponents(inputs: Vector[EndpointInput.Basic[_, _]]): Vector[String] = {
    inputs
      .collect {
        case EndpointInput.PathCapture(name, _, _) => Left(name)
        case EndpointInput.FixedPath(s, _, _)      => Right(s)
      }
      .foldLeft((Vector.empty[String], 1)) { case ((acc, i), component) =>
        component match {
          case Left(None)    => throw new IllegalStateException("All path captures should be named")
          case Left(Some(p)) => (acc :+ p, i)
          case Right(p)      => (acc :+ p, i)
        }
      }
      ._1
  }
}
