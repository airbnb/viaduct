package viaduct.tenant.codegen.kotlingen.bytecode

// See README.md for the patterns that guided this file

import viaduct.apiannotations.TestingApi
import viaduct.graphql.schema.ViaductSchema

@TestingApi
class InputTypeDescriptor(
    val className: String,
    /** We use this for _Arguments types as well as input types, in which
     *  case the "fields" are really FieldArgs.
     */
    val fields: Iterable<ViaductSchema.HasDefaultValue>,
    /** The TypeDef that this InputTypeDescriptor originated from, if one exists */
    val def: ViaductSchema.TypeDef?
)
