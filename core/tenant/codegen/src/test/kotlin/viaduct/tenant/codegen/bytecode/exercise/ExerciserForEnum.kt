package viaduct.tenant.codegen.bytecode.exercise

import java.lang.Enum
import viaduct.graphql.schema.ViaductSchema

internal fun Exerciser.exerciseEnum(expected: ViaductSchema.Enum) {
    val actual = classResolver.mainClassFor(expected.name)

    // If this ain't true don't bother with rest
    if (!check.isNotNull(actual, "ENUM_EXISTS")) return
    if (!check.isTrue(actual.isEnum, "ENUM_IS_ENUM")) return

    expected.values.mapIndexed { expectedOrd, expectedVal ->
        check.withContext(expectedVal.name) {
            check.doesNotThrow("ENUM_GET_FIELD_WORKS") {
                actual.getField(expectedVal.name)
            }.ifNoThrow { actualField ->
                val actualFieldVal = actualField.get(null) as Enum<*>
                check.isEqualTo(expectedVal.name, actualFieldVal.name(), "ENUM_NAME_AGREE")
                check.isEqualTo(expectedOrd, actualFieldVal.ordinal(), "ENUM_ORDS_AGREE")
                check.isEqualTo(expectedVal.name, actualFieldVal.toString(), "ENUM_TOSTRING_AGREE")
                @Suppress("UNCHECKED_CAST")
                val valueOf =
                    Enum.valueOf(actualFieldVal::class.java as Class<out kotlin.Enum<*>>, expectedVal.name)
                check.isSameInstanceAs(valueOf, actualFieldVal, "ENUM_ENUM_VALUE_OF")
            }
        }
    }
}
