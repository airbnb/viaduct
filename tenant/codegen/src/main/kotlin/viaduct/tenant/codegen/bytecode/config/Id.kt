package viaduct.tenant.codegen.bytecode.config

import graphql.language.StringValue
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.IdOf.Companion.idOf

/**
 * Whether the type is the GraphQL `ID` scalar type
 */
internal val ViaductExtendedSchema.TypeDef.isID: Boolean
    get() = kind == ViaductExtendedSchema.TypeDefKind.SCALAR && name == "ID"

/**
 * A more ergonomic representation of the @idOf directive
 */
data class IdOf(val type: String) {
    companion object {
        private val name: String = "idOf"

        private fun parse(dir: ViaductSchema.AppliedDirective): IdOf {
            require(dir.name == name)
            return IdOf((dir.arguments["type"] as StringValue).value)
        }

        val Iterable<ViaductSchema.AppliedDirective>.idOf: IdOf?
            get() = firstNotNullOfOrNull { if (it.name == name) parse(it) else null }
    }
}

/**
 * When generating a Kotlin type for a field or argument, this
 * function tells you the "Foo" in `GlobalID<Foo>` - or returns null
 * if you should just use `String` instead.
 */
fun ViaductExtendedSchema.HasDefaultValue.grtNameForIdParam(): String? {
    val isNodeIdField = isNodeIdField(this)
    val idOf = this.appliedDirectives.idOf

    return if (isNodeIdField) {
        require(idOf == null) {
            "@idOf may not be used on the `id` field of a Node implementation"
        }
        this.containingDef.name
    } else if (idOf != null) {
        idOf.type
    } else {
        null
    }
}

private fun isNodeIdField(field: ViaductExtendedSchema.HasDefaultValue): Boolean {
    val containerType = field.containingDef as? ViaductExtendedSchema.TypeDef
    return field.name == "id" && containerType?.isNode == true
}
