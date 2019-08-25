package tapir.docs.openapi.schema

import tapir.openapi.OpenAPI.ReferenceOr
import tapir.openapi.{SchemaType, Schema => OSchema}
import tapir.{Validator, Schema => TSchema}

class ObjectSchemas(
    tschemaToOSchema: TSchemaToOSchema,
    schemaReferenceMapper: SchemaReferenceMapper
) {
  def apply(schemaWithValidator: (TSchema, Validator[_])): ReferenceOr[OSchema] = {
    schemaWithValidator match {
      case (TSchema.SArray(o: TSchema.SObject), _) =>
        Right(
          OSchema(SchemaType.Array).copy(items = Some(Left(schemaReferenceMapper.map(o.info))))
        )
      case (o: TSchema.SObject, _) => Left(schemaReferenceMapper.map(o.info))
      case _                       => tschemaToOSchema(schemaWithValidator)
    }
  }
}
