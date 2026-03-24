package viaduct.graphql.schema

/**
 * Extension properties for accessing the unfiltered definition from a filtered schema.
 *
 * When a schema is created via [filteredSchema], each node's [SchemaWithData.Def.data]
 * property holds the corresponding unfiltered [ViaductSchema.Def]. These extension
 * properties provide convenient access by casting from [data].
 *
 * All properties are named [unfilteredDef] with the return type narrowed based on
 * the receiver type.
 */

/** The unfiltered definition that this filtered definition wraps. */
internal val SchemaWithData.Def.unfilteredDef: ViaductSchema.Def
    get() = data as ViaductSchema.Def

/** The unfiltered directive that this filtered directive wraps. */
internal val SchemaWithData.Directive.unfilteredDef: ViaductSchema.Directive
    get() = data as ViaductSchema.Directive

/** The unfiltered type definition that this filtered type definition wraps. */
internal val SchemaWithData.TypeDef.unfilteredDef: ViaductSchema.TypeDef
    get() = data as ViaductSchema.TypeDef

/** The unfiltered scalar that this filtered scalar wraps. */
internal val SchemaWithData.Scalar.unfilteredDef: ViaductSchema.Scalar
    get() = data as ViaductSchema.Scalar

/** The unfiltered enum that this filtered enum wraps. */
internal val SchemaWithData.Enum.unfilteredDef: ViaductSchema.Enum
    get() = data as ViaductSchema.Enum

/** The unfiltered union that this filtered union wraps. */
internal val SchemaWithData.Union.unfilteredDef: ViaductSchema.Union
    get() = data as ViaductSchema.Union

/** The unfiltered interface that this filtered interface wraps. */
internal val SchemaWithData.Interface.unfilteredDef: ViaductSchema.Interface
    get() = data as ViaductSchema.Interface

/** The unfiltered object that this filtered object wraps. */
internal val SchemaWithData.Object.unfilteredDef: ViaductSchema.Object
    get() = data as ViaductSchema.Object

/** The unfiltered input that this filtered input wraps. */
internal val SchemaWithData.Input.unfilteredDef: ViaductSchema.Input
    get() = data as ViaductSchema.Input

/** The unfiltered field that this filtered field wraps. */
internal val SchemaWithData.Field.unfilteredDef: ViaductSchema.Field
    get() = data as ViaductSchema.Field

/** The unfiltered enum value that this filtered enum value wraps. */
internal val SchemaWithData.EnumValue.unfilteredDef: ViaductSchema.EnumValue
    get() = data as ViaductSchema.EnumValue

/** The unfiltered directive arg that this filtered directive arg wraps. */
internal val SchemaWithData.DirectiveArg.unfilteredDef: ViaductSchema.DirectiveArg
    get() = data as ViaductSchema.DirectiveArg

/** The unfiltered field arg that this filtered field arg wraps. */
internal val SchemaWithData.FieldArg.unfilteredDef: ViaductSchema.FieldArg
    get() = data as ViaductSchema.FieldArg
