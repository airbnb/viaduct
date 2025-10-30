package viaduct.graphql.schema.test

import viaduct.graphql.schema.ViaductSchema
import viaduct.invariants.InvariantChecker

/** Both [visitDef] and then (sequentially) the more specific visitor are
 *  called for each [ViaductSchema.Def] in the schema.  In the case of nested
 *  items, calls are made in a stack-like manner, i.e., [visitDef] is called
 *  on the parent, [visitDef] on the child, then
 *  kind-specific [visitXyz] on the child, then kind-specific [visitAbc on
 *  the parent.  Before any of these are called some basic sanity checking is
 *  done, ie, both params will have the same name, class, etc.
 */
interface ExtraDiffsVisitor {
    fun visitDirective(
        expected: ViaductSchema.Directive,
        actual: ViaductSchema.Directive,
        c: InvariantChecker
    ) {}

    fun visitArg(
        expected: ViaductSchema.Arg,
        actual: ViaductSchema.Arg,
        c: InvariantChecker
    ) {}

    fun visitDef(
        expected: ViaductSchema.Def,
        actual: ViaductSchema.Def,
        c: InvariantChecker
    ) {}

    fun visitEnum(
        expected: ViaductSchema.Enum,
        actual: ViaductSchema.Enum,
        c: InvariantChecker
    ) {}

    fun visitEnumValue(
        expected: ViaductSchema.EnumValue,
        actual: ViaductSchema.EnumValue,
        c: InvariantChecker
    ) {}

    fun visitField(
        expected: ViaductSchema.Field,
        actual: ViaductSchema.Field,
        c: InvariantChecker
    ) {}

    fun visitInput(
        expected: ViaductSchema.Input,
        actual: ViaductSchema.Input,
        c: InvariantChecker
    ) {}

    fun visitInterface(
        expected: ViaductSchema.Interface,
        actual: ViaductSchema.Interface,
        c: InvariantChecker
    ) {}

    fun visitObject(
        expected: ViaductSchema.Object,
        actual: ViaductSchema.Object,
        c: InvariantChecker
    ) {}

    fun visitScalar(
        expected: ViaductSchema.Scalar,
        actual: ViaductSchema.Scalar,
        c: InvariantChecker
    ) {}

    fun visitUnion(
        expected: ViaductSchema.Union,
        actual: ViaductSchema.Union,
        c: InvariantChecker
    ) {}
}
