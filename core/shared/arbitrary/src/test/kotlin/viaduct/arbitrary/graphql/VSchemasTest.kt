@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.removeEdgecases
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkViaductSchemaInvariants

class VSchemasTest : KotestPropertyBase(iterations = 100) {
    @Test
    fun `generates valid VSchemas`(): Unit =
        runBlocking {
            Arb.vSchema().checkInvariants { schema, check ->
                checkViaductSchemaInvariants(schema, check)
            }
        }

    @Test
    fun `generates valid VSchemas -- covers every TypeDef kind`(): Unit =
        runBlocking {
            // removeEdgecases: Arb.graphQLNames declares GraphQLNames.empty as an edgecase, which
            // yields a near-empty schema that no config can make extensive.
            Arb.vSchema(coverageConfig).removeEdgecases().checkInvariants(iter = 10) { schema, check ->
                checkViaductSchemaInvariants(schema, check)

                val defs = schema.types.values
                val objects = defs.filterIsInstance<ViaductSchema.Object>()
                val interfaces = defs.filterIsInstance<ViaductSchema.Interface>()
                val unions = defs.filterIsInstance<ViaductSchema.Union>()
                val inputs = defs.filterIsInstance<ViaductSchema.Input>()
                val enums = defs.filterIsInstance<ViaductSchema.Enum>()
                val scalars = defs.filterIsInstance<ViaductSchema.Scalar>()

                println(
                    "[coverageConfig] " +
                        "objects=${objects.size} " +
                        "interfaces=${interfaces.size} " +
                        "unions=${unions.size} " +
                        "inputs=${inputs.size} " +
                        "enums=${enums.size} " +
                        "scalars=${scalars.size}"
                )

                check.isTrue(objects.isNotEmpty(), "expected at least one Object")
                check.isTrue(interfaces.isNotEmpty(), "expected at least one Interface")
                check.isTrue(unions.any { it.possibleObjectTypes.size > 1 }, "expected a Union with multiple members")
                check.isTrue(inputs.isNotEmpty(), "expected at least one Input")
                check.isTrue(enums.isNotEmpty(), "expected at least one Enum")
                // builtin scalars alone account for String/Int/Float/Boolean/ID;
                // GenCustomScalars should push this beyond that baseline
                check.isTrue(scalars.size > 5, "expected at least one custom Scalar beyond the 5 builtins")
                check.isTrue(
                    interfaces.all { iface -> objects.any { obj -> obj.supers.any { it.name == iface.name } } },
                    "expected every Interface to have an implementing Object"
                )
            }
        }

    @Test
    fun `TypeExpr methods do not throw for non-list types`(): Unit =
        runBlocking {
            Arb
                .vSchemaTypeExpr()
                .filter { !it.isList }
                .checkInvariants { type, check ->
                    check.doesNotThrow("unexpected err") {
                        type.nullableAtDepth(0)
                        type.isList
                        type.listDepth
                    }
                }
        }
}
