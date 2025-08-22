package viaduct.tenant.codegen.kotlingen.bytecode

// See README.md for the patterns that guided this file

import viaduct.graphql.schema.ViaductExtendedSchema

class InputTypeDescriptor(
    val className: String,
    /** We use this for _Arguments types as well as input types, in which
     *  case the "fields" are really FieldArgs.
     */
    val fields: Iterable<ViaductExtendedSchema.HasDefaultValue>,
    /** The TypeDef that this InputTypeDescriptor originated from, if one exists */
    val def: ViaductExtendedSchema.TypeDef?
)
