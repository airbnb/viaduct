package viaduct.graphql.schema.test

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.graphql.schema.ViaductReverseSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/**
 * Black-box invariant tests for [ViaductReverseSchema].
 *
 * Iterates over all [TestSchemas], builds a [ViaductReverseSchema] for each,
 * and verifies structural invariants without hand-writing expected values.
 */
class BlackBoxReverseSchemaTest {
    private fun assertInvariants(case: TestSchemas.Case) {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(case.fullSdl))
        val rev = ViaductReverseSchema.from(schema)

        // Invariant 1: Every Field with a non-builtin baseTypeDef appears
        // in inboundFields for that type.
        for (typeDef in schema.types.values) {
            if (typeDef is ViaductSchema.Record) {
                for (field in typeDef.fields) {
                    val target = field.type.baseTypeDef
                    assertTrue(
                        rev.inboundFields(target).any { it === field },
                        "${case.name}: Field ${typeDef.name}.${field.name} " +
                            "should appear in inboundFields(${target.name})"
                    )
                }
            }
        }

        // Invariant 2: Every OutputRecord with supers appears in
        // inboundTypeDefs for each super.
        for (typeDef in schema.types.values) {
            if (typeDef is ViaductSchema.OutputRecord) {
                for (iface in typeDef.supers) {
                    assertTrue(
                        rev.inboundTypeDefs(iface).any { it === typeDef },
                        "${case.name}: ${typeDef.name} should appear in " +
                            "inboundTypeDefs(${iface.name})"
                    )
                }
            }
        }

        // Invariant 3: Every union member object has the union in its
        // inboundTypeDefs.
        for (typeDef in schema.types.values) {
            if (typeDef is ViaductSchema.Union) {
                for (obj in typeDef.possibleObjectTypes) {
                    assertTrue(
                        rev.inboundTypeDefs(obj).any { it === typeDef },
                        "${case.name}: Union ${typeDef.name} should appear " +
                            "in inboundTypeDefs(${obj.name})"
                    )
                }
            }
        }

        // Invariant 4: Every appliedDirective produces the applying def
        // in inboundDefs for that directive.
        fun checkDirectiveApps(
            def: ViaductSchema.Def,
            label: String
        ) {
            for (ad in def.appliedDirectives) {
                assertTrue(
                    rev.inboundDefs(ad.directive).any { it === def },
                    "${case.name}: $label should appear in " +
                        "inboundDefs(${ad.directive.name})"
                )
            }
        }
        for (typeDef in schema.types.values) {
            checkDirectiveApps(typeDef, typeDef.name)
            if (typeDef is ViaductSchema.Record) {
                for (field in typeDef.fields) {
                    checkDirectiveApps(field, "${typeDef.name}.${field.name}")
                    for (arg in field.args) {
                        checkDirectiveApps(arg, "${typeDef.name}.${field.name}.${arg.name}")
                    }
                }
            }
            if (typeDef is ViaductSchema.Enum) {
                for (value in typeDef.values) {
                    checkDirectiveApps(value, "${typeDef.name}.${value.name}")
                }
            }
        }
        for (directive in schema.directives.values) {
            checkDirectiveApps(directive, "@${directive.name}")
            for (arg in directive.args) {
                checkDirectiveApps(arg, "@${directive.name}.${arg.name}")
            }
        }

        // Invariant 5: referencingTopLevelDefs is consistent with
        // inboundDefs (chasing containment produces the same result).
        for (typeDef in schema.types.values) {
            val topLevelDef = typeDef as ViaductSchema.TopLevelDef
            val fromReferencing = rev.referencingTopLevelDefs(topLevelDef)
                .map { it.name }.toSet()
            val fromInbound = rev.inboundDefs(topLevelDef)
                .map { toTopLevelDefName(it) }.toSet()
            assertTrue(
                fromReferencing == fromInbound,
                "${case.name}: referencingTopLevelDefs(${typeDef.name}) " +
                    "[$fromReferencing] != inboundDefs chased [$fromInbound]"
            )
        }
        for (directive in schema.directives.values) {
            val topLevelDef = directive as ViaductSchema.TopLevelDef
            val fromReferencing = rev.referencingTopLevelDefs(topLevelDef)
                .map { it.name }.toSet()
            val fromInbound = rev.inboundDefs(topLevelDef)
                .map { toTopLevelDefName(it) }.toSet()
            assertTrue(
                fromReferencing == fromInbound,
                "${case.name}: referencingTopLevelDefs(@${directive.name}) " +
                    "[$fromReferencing] != inboundDefs chased [$fromInbound]"
            )
        }
    }

    @Test
    @DisplayName("DIRECTIVE schemas")
    fun `reverse schema invariants for directive schemas`() {
        assertAll(TestSchemas.DIRECTIVE.map { Executable { assertInvariants(it) } })
    }

    @Test
    @DisplayName("ENUM schemas")
    fun `reverse schema invariants for enum schemas`() {
        assertAll(TestSchemas.ENUM.map { Executable { assertInvariants(it) } })
    }

    @Test
    @DisplayName("INPUT schemas")
    fun `reverse schema invariants for input schemas`() {
        assertAll(TestSchemas.INPUT.map { Executable { assertInvariants(it) } })
    }

    @Test
    @DisplayName("INTERFACE schemas")
    fun `reverse schema invariants for interface schemas`() {
        assertAll(TestSchemas.INTERFACE.map { Executable { assertInvariants(it) } })
    }

    @Test
    @DisplayName("OBJECT schemas")
    fun `reverse schema invariants for object schemas`() {
        assertAll(TestSchemas.OBJECT.map { Executable { assertInvariants(it) } })
    }

    @Test
    @DisplayName("SCALAR schemas")
    fun `reverse schema invariants for scalar schemas`() {
        assertAll(TestSchemas.SCALAR.map { Executable { assertInvariants(it) } })
    }

    @Test
    @DisplayName("UNION schemas")
    fun `reverse schema invariants for union schemas`() {
        assertAll(TestSchemas.UNION.map { Executable { assertInvariants(it) } })
    }

    @Test
    @DisplayName("ROOT schemas")
    fun `reverse schema invariants for root schemas`() {
        assertAll(TestSchemas.ROOT.map { Executable { assertInvariants(it) } })
    }

    @Test
    @DisplayName("COMPLEX schemas")
    fun `reverse schema invariants for complex schemas`() {
        assertAll(TestSchemas.COMPLEX.map { Executable { assertInvariants(it) } })
    }

    companion object {
        private fun toTopLevelDefName(def: ViaductSchema.Def): String =
            when (def) {
                is ViaductSchema.FieldArg ->
                    (def.containingDef.containingDef as ViaductSchema.TopLevelDef).name
                is ViaductSchema.DirectiveArg -> def.containingDef.name
                is ViaductSchema.Field -> def.containingDef.name
                is ViaductSchema.EnumValue -> def.containingDef.name
                is ViaductSchema.TopLevelDef -> def.name
                else -> error("Unknown def type: ${def::class}")
            }
    }
}
