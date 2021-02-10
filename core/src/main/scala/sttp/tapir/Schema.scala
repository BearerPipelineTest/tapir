package sttp.tapir

import magnolia.Magnolia
import sttp.model.Part
import sttp.tapir.SchemaType._
import sttp.tapir.generic.Derived
import sttp.tapir.generic.internal.OneOfMacro.oneOfMacro
import sttp.tapir.generic.internal.{SchemaMagnoliaDerivation, SchemaMapMacro}
import sttp.tapir.internal.ModifySchemaMacro

import java.io.InputStream
import java.math.{BigDecimal => JBigDecimal}
import java.nio.ByteBuffer
import java.time._
import java.util.concurrent.TimeUnit
import java.util.{Date, UUID}
import scala.annotation.{StaticAnnotation, implicitNotFound}

/** Describes the type `T`: its low-level representation, meta-data and validation rules.
  * @param format The name of the format of the low-level representation of `T`.
  */
@implicitNotFound(
  msg = """Could not find Schema for type ${T}.
Since 0.17.0 automatic derivation requires the following import: `import sttp.tapir.generic.auto._`
You can find more details in the docs: https://tapir.softwaremill.com/en/latest/endpoint/customtypes.html#schema-derivation"""
)
case class Schema[T](
    schemaType: SchemaType,
    isOptional: Boolean = false,
    description: Option[String] = None,
    // The default value together with the value encoded to a raw format, which will then be directly rendered as a
    // string in documentation. This is needed as codecs for nested types aren't available. Similar to Validator.EncodeToRaw
    default: Option[(T, Option[Any])] = None,
    format: Option[String] = None,
    encodedExample: Option[Any] = None,
    deprecated: Boolean = false,
    validator: Validator[T] = Validator.pass[T]
) {

  def map[TT](f: T => Option[TT])(g: TT => T): Schema[TT] = copy(
    default = default.flatMap { case (t, raw) =>
      f(t).map(tt => (tt, raw))
    },
    validator = validator.contramap(g)
  )

  /** Returns an optional version of this schema, with `isOptional` set to true.
    */
  def asOption: Schema[Option[T]] = copy(isOptional = true, default = None, validator = validator.asOptionElement)

  /** Returns an array version of this schema, with the schema type wrapped in [[SArray]].
    * Sets `isOptional` to true as the collection might be empty.
    */
  def asArray: Schema[Array[T]] =
    Schema(schemaType = SArray(this), isOptional = true, format = None, deprecated = deprecated, validator = validator.asArrayElements)

  /** Returns a collection version of this schema, with the schema type wrapped in [[SArray]].
    * Sets `isOptional` to true as the collection might be empty.
    */
  def asIterable[C[X] <: Iterable[X]]: Schema[C[T]] =
    Schema(
      schemaType = SArray(this),
      isOptional = true,
      format = None,
      deprecated = deprecated,
      validator = validator.asIterableElements[C]
    )

  def description(d: String): Schema[T] = copy(description = Some(d))

  def encodedExample(e: Any): Schema[T] = copy(encodedExample = Some(e))

  def default(t: T, raw: Option[Any] = None): Schema[T] = copy(default = Some((t, raw)), isOptional = true)

  def format(f: String): Schema[T] = copy(format = Some(f))

  def deprecated(d: Boolean): Schema[T] = copy(deprecated = d)

  def show: String = s"schema is $schemaType${if (isOptional) " (optional)" else ""}"

  def modifyUnsafe[U](fields: String*)(modify: Schema[U] => Schema[U]): Schema[T] = modifyAtPath(fields.toList, modify)

  def modify[U](path: T => U)(modification: Schema[U] => Schema[U]): Schema[T] = macro ModifySchemaMacro.modifyMacro[T, U]

  private def modifyAtPath[U](fieldPath: List[String], modify: Schema[U] => Schema[U]): Schema[T] =
    fieldPath match {
      case Nil => modify(this.asInstanceOf[Schema[U]]).asInstanceOf[Schema[T]] // we don't have type-polymorphic functions
      case f :: fs =>
        val schemaType2 = schemaType match {
          case SArray(element) if f == Schema.ModifyCollectionElements => SArray(element.modifyAtPath(fs, modify))
          case s @ SProduct(_, fields) =>
            s.copy(fields = fields.toList.map { case field @ (fieldName, fieldSchema) =>
              if (fieldName.name == f) (fieldName, fieldSchema.modifyAtPath(fs, modify)) else field
            })
          case s @ SOpenProduct(_, valueSchema) if f == Schema.ModifyCollectionElements =>
            s.copy(valueSchema = valueSchema.modifyAtPath(fs, modify))
          case s @ SCoproduct(_, schemas, _) => s.copy(schemas = schemas.map(_.modifyAtPath(fieldPath, modify)))
          case _                             => schemaType
        }
        copy(schemaType = schemaType2)
    }

  def validate(v: Validator[T]): Schema[T] = copy(validator = validator.and(v))
}
class description(val text: String) extends StaticAnnotation

class encodedExample(val example: Any) extends StaticAnnotation

class default[T](val default: T) extends StaticAnnotation

class format(val format: String) extends StaticAnnotation

class deprecated extends StaticAnnotation

class encodedName(val name: String) extends StaticAnnotation

class validate[T](val v: Validator[T]) extends StaticAnnotation

object Schema extends SchemaExtensions with SchemaMagnoliaDerivation with LowPrioritySchema {
  private val DefaultExampleZoneOffset = ZoneOffset.UTC
  private val DefaultExampleJavaDuration = Duration.ofDays(1L)
  private val DefaultExampleScalaDuration = scala.concurrent.duration.Duration(1, TimeUnit.DAYS)
  private val DefaultExampleUUID = UUID.fromString("130770a3-d156-453e-be8f-9dad82432c15")
  private val DefaultExampleLocalTime = LocalTime.of(9,41)
  private val DefaultExampleOffsetTime = OffsetTime.of(DefaultExampleLocalTime, DefaultExampleZoneOffset)
  private val DefaultExampleLocalDateTime = LocalDateTime.of(2021,1,1,9,41)

  val ModifyCollectionElements = "each"

  /** Creates a schema for type `T`, where the low-level representation is a `String`.
    */
  def string[T]: Schema[T] = Schema(SString)

  /** Creates a schema for type `T`, where the low-level representation is binary.
    */
  def binary[T]: Schema[T] = Schema(SBinary)

  implicit val schemaForString: Schema[String] = Schema(SString)
  implicit val schemaForByte: Schema[Byte] = Schema(SInteger)
  implicit val schemaForShort: Schema[Short] = Schema(SInteger)
  implicit val schemaForInt: Schema[Int] = Schema(SInteger)
  implicit val schemaForLong: Schema[Long] = Schema(SInteger).format("int64")
  implicit val schemaForFloat: Schema[Float] = Schema(SNumber).format("float")
  implicit val schemaForDouble: Schema[Double] = Schema(SNumber).format("double")
  implicit val schemaForBoolean: Schema[Boolean] = Schema(SBoolean)
  implicit val schemaForUnit: Schema[Unit] = Schema(SProduct.Empty)
  implicit val schemaForFile: Schema[TapirFile] = Schema(SBinary)
  implicit val schemaForByteArray: Schema[Array[Byte]] = Schema(SBinary)
  implicit val schemaForByteBuffer: Schema[ByteBuffer] = Schema(SBinary)
  implicit val schemaForInputStream: Schema[InputStream] = Schema(SBinary)
  implicit val schemaForInstant: Schema[Instant] = Schema(SDateTime)
  implicit val schemaForZonedDateTime: Schema[ZonedDateTime] = Schema(SDateTime)
  implicit val schemaForOffsetDateTime: Schema[OffsetDateTime] = Schema(SDateTime)
  implicit val schemaForDate: Schema[Date] = Schema(SDateTime)
  implicit val schemaForLocalDate: Schema[LocalDate] = Schema(SDate)
  implicit val schemaForLocalDateTime: Schema[LocalDateTime] = Schema(SString).encodedExample(DefaultExampleLocalDateTime)
  implicit val schemaForZoneOffset: Schema[ZoneOffset] = Schema(SString).encodedExample(DefaultExampleZoneOffset)
  implicit val schemaForJavaDuration: Schema[Duration] = Schema(SString).encodedExample(DefaultExampleJavaDuration)
  implicit val schemaForLocalTime: Schema[LocalTime] = Schema(SString).encodedExample(DefaultExampleLocalTime)
  implicit val schemaForOffsetTime: Schema[OffsetTime] = Schema(SString).encodedExample(DefaultExampleOffsetTime)
  implicit val schemaForScalaDuration: Schema[scala.concurrent.duration.Duration] =
    Schema(SString).encodedExample(DefaultExampleScalaDuration)
  implicit val schemaForUUID: Schema[UUID] = Schema(SString).encodedExample(DefaultExampleUUID)
  implicit val schemaForBigDecimal: Schema[BigDecimal] = Schema(SString)
  implicit val schemaForJBigDecimal: Schema[JBigDecimal] = Schema(SString)

  implicit def schemaForOption[T: Schema]: Schema[Option[T]] = implicitly[Schema[T]].asOption
  implicit def schemaForArray[T: Schema]: Schema[Array[T]] = implicitly[Schema[T]].asArray
  implicit def schemaForIterable[T: Schema, C[X] <: Iterable[X]]: Schema[C[T]] = implicitly[Schema[T]].asIterable[C]
  implicit def schemaForPart[T: Schema]: Schema[Part[T]] = Schema[Part[T]](implicitly[Schema[T]].schemaType)
  implicit def schemaForMap[V: Schema]: Schema[Map[String, V]] = macro SchemaMapMacro.schemaForMap[Map[String, V], V]

  def oneOfUsingField[E, V](extractor: E => V, asString: V => String)(mapping: (V, Schema[_])*): Schema[E] = macro oneOfMacro[E, V]
  def derived[T]: Schema[T] = macro Magnolia.gen[T]
}

trait LowPrioritySchema {
  implicit def derivedSchema[T](implicit derived: Derived[Schema[T]]): Schema[T] = derived.value
}
