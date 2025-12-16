package viaduct.tenant.codegen.bytecode.exercise

import viaduct.graphql.schema.ViaductSchema

internal fun Exerciser.exerciseEnumV2(expected: ViaductSchema.Enum) {
    // enums are nearly identical between v1 and v2. For simplicity, let's start by invoking the V1 exercisers
    exerciseEnum(expected)

    // A difference between v1 and v2 enums is that the v2 enums include a Reflection object.
    // Exercise it.
    exerciseReflectionObject(classResolver.mainClassFor(expected.name).kotlin, expected)
}
