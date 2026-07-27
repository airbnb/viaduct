package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.isNode
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates that any interface with an `id` field that is implemented alongside `Node` also
 * implements `Node` itself.
 *
 * If a type implements `Node` and also implements another interface `Foo` that declares an `id`
 * field, `Foo` must implement `Node` too. Otherwise, a caller holding a `Foo` reference can't tell
 * whether `Foo.id` carries Viaduct's global id semantics, since some implementors of `Foo` may be
 * `Node`s and others may not.
 */
class NodeInterfaceIdConsistencyRule : ValidationRule(
    id = "NodeInterfaceIdConsistency",
    description = "Interfaces with an id field that are implemented alongside Node must implement Node"
) {
    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) = checkOutputRecord(ctx, obj)

    override fun visitInterface(
        ctx: ValidationContext,
        iface: ViaductSchema.Interface
    ) = checkOutputRecord(ctx, iface)

    private fun checkOutputRecord(
        ctx: ValidationContext,
        outputRecord: ViaductSchema.OutputRecord
    ) {
        if (!outputRecord.isNode) return

        outputRecord.supers
            .filter { !it.isNode && it.field("id") != null }
            .forEach { superInterface ->
                ctx.reportError(
                    code = ValidationErrorCodes.NODE_INTERFACE_ID_INCONSISTENT,
                    message = "${outputRecord.name} implements Node and also implements interface " +
                        "${superInterface.name}, which declares an id field but does not implement Node. " +
                        "${superInterface.name} must implement Node.",
                    location = SchemaLocation.ofType(outputRecord.name)
                )
            }
    }
}
