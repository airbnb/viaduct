package viaduct.graphql.schema.validation

import viaduct.graphql.schema.ViaductSchema

/**
 * A validation rule that participates in schema traversal.
 *
 * Subclasses override the visit methods they need for validation.
 * All visit methods have default no-op implementations.
 *
 * @param id Rule identifier for filtering/enabling rules and error reporting
 * @param description Human-readable description of what this rule validates
 */
abstract class ValidationRule(
    val id: String,
    val description: String = ""
) {
    // Schema-level
    open fun visitSchema(ctx: ValidationContext) {}

    // Directive definitions
    open fun visitDirective(
        ctx: ValidationContext,
        directive: ViaductSchema.Directive
    ) {}

    open fun visitDirectiveArg(
        ctx: ValidationContext,
        arg: ViaductSchema.DirectiveArg
    ) {}

    // Type definitions
    open fun visitTypeDef(
        ctx: ValidationContext,
        typeDef: ViaductSchema.TypeDef
    ) {}

    open fun visitScalar(
        ctx: ValidationContext,
        scalar: ViaductSchema.Scalar
    ) {}

    open fun visitEnum(
        ctx: ValidationContext,
        enum: ViaductSchema.Enum
    ) {}

    open fun visitEnumValue(
        ctx: ValidationContext,
        value: ViaductSchema.EnumValue
    ) {}

    open fun visitUnion(
        ctx: ValidationContext,
        union: ViaductSchema.Union
    ) {}

    open fun visitInput(
        ctx: ValidationContext,
        input: ViaductSchema.Input
    ) {}

    open fun visitInterface(
        ctx: ValidationContext,
        iface: ViaductSchema.Interface
    ) {}

    open fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {}

    // Field and argument definitions
    open fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {}

    open fun visitFieldArg(
        ctx: ValidationContext,
        arg: ViaductSchema.FieldArg
    ) {}
}
