package viaduct.graphql.schema

import viaduct.utils.collections.HMap

/**
 * Extension properties for accessing the unfiltered definition from a filtered schema.
 *
 * When a schema is created via [filteredSchema], each node's [SchemaWithData.Def.holder]
 * property holds the corresponding unfiltered [ViaductSchema.Def] under a private key.
 *
 * All properties are named [unfilteredDef] with the return type narrowed based on
 * the receiver type.
 */

private val filteredSchemaKey = HMap.Key.of<Any?>("FilteredSchema")

internal fun filteredSchemaHolder(value: Any?): HMap =
    HMap.Builder()
        .put(filteredSchemaKey, value)
        .build()

/** The unfiltered definition that this filtered definition wraps. */
internal val SchemaWithData.Def.unfilteredDef: ViaductSchema.Def
    get() = holder[filteredSchemaKey] as ViaductSchema.Def

internal fun SchemaWithData.Def.unfilteredDefOrNull(): ViaductSchema.Def? =
    try {
        holder[filteredSchemaKey] as? ViaductSchema.Def
    } catch (_: NoSuchElementException) {
        null
    }

/** The unfiltered directive that this filtered directive wraps. */
internal val SchemaWithData.Directive.unfilteredDef: ViaductSchema.Directive
    get() = (this as SchemaWithData.Def).unfilteredDef as ViaductSchema.Directive

/** The unfiltered scalar that this filtered scalar wraps. */
internal val SchemaWithData.Scalar.unfilteredDef: ViaductSchema.Scalar
    get() = (this as SchemaWithData.Def).unfilteredDef as ViaductSchema.Scalar

/** The unfiltered enum that this filtered enum wraps. */
internal val SchemaWithData.Enum.unfilteredDef: ViaductSchema.Enum
    get() = (this as SchemaWithData.Def).unfilteredDef as ViaductSchema.Enum

/** The unfiltered union that this filtered union wraps. */
internal val SchemaWithData.Union.unfilteredDef: ViaductSchema.Union
    get() = (this as SchemaWithData.Def).unfilteredDef as ViaductSchema.Union

/** The unfiltered interface that this filtered interface wraps. */
internal val SchemaWithData.Interface.unfilteredDef: ViaductSchema.Interface
    get() = (this as SchemaWithData.Def).unfilteredDef as ViaductSchema.Interface

/** The unfiltered object that this filtered object wraps. */
internal val SchemaWithData.Object.unfilteredDef: ViaductSchema.Object
    get() = (this as SchemaWithData.Def).unfilteredDef as ViaductSchema.Object

/** The unfiltered input that this filtered input wraps. */
internal val SchemaWithData.Input.unfilteredDef: ViaductSchema.Input
    get() = (this as SchemaWithData.Def).unfilteredDef as ViaductSchema.Input
