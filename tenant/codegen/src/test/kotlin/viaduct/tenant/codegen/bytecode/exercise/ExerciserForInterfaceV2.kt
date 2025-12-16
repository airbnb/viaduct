package viaduct.tenant.codegen.bytecode.exercise

import viaduct.graphql.schema.ViaductSchema

internal fun Exerciser.exerciseInterface(expected: ViaductSchema.Interface) {
    val cls = check.tryResolveClass("CLASS_EXISTS", classResolver) {
        mainClassFor(expected.name)
    }
    cls ?: return

    exerciseReflectionObject(cls.kotlin, expected)
}
