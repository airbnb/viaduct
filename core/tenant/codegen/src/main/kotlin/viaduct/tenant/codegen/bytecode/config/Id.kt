package viaduct.tenant.codegen.bytecode.config

import viaduct.codegen.SchemaAnalysis
import viaduct.graphql.schema.ViaductSchema

/**
 * Whether the type is the GraphQL `ID` scalar type.
 * Delegates to the shared language-neutral [SchemaAnalysis.isIdScalar].
 */
internal val ViaductSchema.TypeDef.isID: Boolean
    get() = SchemaAnalysis.isIdScalar(this)

/**
 * When generating a Kotlin type for a field or argument, this
 * function tells you the "Foo" in `GlobalID<Foo>` - or returns null
 * if you should just use `String` instead.
 * Delegates to the shared language-neutral [SchemaAnalysis.globalIdTargetTypeName].
 */
fun ViaductSchema.HasDefaultValue.grtNameForIdParam(): String? = SchemaAnalysis.globalIdTargetTypeName(this)
