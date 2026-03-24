package viaduct.tenant.codegen.bytecode.exercise

import viaduct.graphql.schema.ViaductSchema

internal fun Exerciser.exerciseUnion(expected: ViaductSchema.Union) {
    val cls = check.tryResolveClass("CLASS_EXISTS", classResolver) {
        mainClassFor(expected.name)
    }
    cls ?: return

    exerciseReflectionObject(cls.kotlin, expected)
}
