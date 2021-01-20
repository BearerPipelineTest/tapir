package sttp.tapir.docs.apispec

import sttp.tapir.{EndpointIO, EndpointInput}

// ideally the parameters would be polymporphic functions returning EI[I] => EI[I]
private[docs] class EndpointInputMapper[S](
    inputMapping: PartialFunction[(EndpointInput.Single[_, _], S), (EndpointInput.Single[_, _], S)],
    ioMapping: PartialFunction[(EndpointIO.Single[_, _], S), (EndpointIO.Single[_, _], S)]
) {
  def mapInput(ei: EndpointInput[_, _], s: S): (EndpointInput[_, _], S) =
    ei match {
      case single: EndpointInput.Single[_, _] => mapInputSingle(single, s)
      case eio: EndpointIO[_, _]              => mapIO(eio, s)
      case EndpointInput.Pair(left, right, combine, split) =>
        val (left2, s2) = mapInput(left, s)
        val (right2, s3) = mapInput(right, s2)

        (EndpointInput.Pair(left2, right2, combine, split), s3)
    }

  private def mapInputSingle(ei: EndpointInput.Single[_, _], s: S): (EndpointInput.Single[_, _], S) =
    ei match {
      case _ if inputMapping.isDefinedAt((ei, s)) => inputMapping((ei, s))
      case EndpointInput.MappedPair(wrapped, c) =>
        val (wrapped2, s2) = mapInput(wrapped, s)
        (EndpointInput.MappedPair(wrapped2.asInstanceOf[EndpointInput.Pair[Any, Any, Any, Any]], c), s2)
      case _ => (ei, s)
    }

  private def mapIO(ei: EndpointIO[_, _], s: S): (EndpointIO[_, _], S) =
    ei match {
      case single: EndpointIO.Single[_, _] => mapIOSingle(single, s)
      case EndpointIO.Pair(left, right, combine, split) =>
        val (left2, s2) = mapIO(left, s)
        val (right2, s3) = mapIO(right, s2)

        (EndpointIO.Pair(left2, right2, combine, split), s3)
    }

  private def mapIOSingle(ei: EndpointIO.Single[_, _], s: S): (EndpointIO.Single[_, _], S) =
    ei match {
      case _ if ioMapping.isDefinedAt((ei, s)) => ioMapping((ei, s))
      case EndpointIO.MappedPair(wrapped, c) =>
        val (wrapped2, s2) = mapIO(wrapped, s)
        (EndpointIO.MappedPair(wrapped2.asInstanceOf[EndpointIO.Pair[Any, Any, Any, Any]], c), s2)
      case _ => (ei, s)
    }
}
