package viaduct.engine.runtime2.resolution

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.bootstrap.resolverRegistryOf
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

class MutationRegistryTest {
    @Test
    fun `all operation roots reject type checkers`() {
        for (owner in listOf("Query", "Mutation", "Subscription")) {
            val failure = assertThrows<IllegalArgumentException> {
                val schemas = ViaductAndGJSchema.fromGraphQLSchema(
                    UnExecutableSchemaGenerator.makeUnExecutableSchema(
                        SchemaParser().parse("type Query { idle: Int } type Mutation { update: Int } type Subscription { updates: Int }"),
                    ),
                )
                val schema = schemas.loweredSchema
                val type = schema.requireType(owner) as ViaductSchema.Object
                resolverRegistryOf(
                    schemas,
                    nodeResolvers = emptyMap(),
                    fieldResolvers = emptyMap(),
                    typeCheckers = mapOf(type to TypeCheckerResolver.of(type, schema.requireQueryTypeDef()) { _, _ -> CheckerResult.Success }),
                    variableProviders = emptyMap(),
                )
            }
            assertTrue(failure.message!!.contains("Root type $owner cannot have a type checker"), failure.message)
        }
    }

    @Test
    fun `mutation field and namespace type checkers reject object and Query fragments`() {
        for (owner in listOf("Mutation", "Group")) {
            for (typeChecker in listOf(false, true)) {
                if (owner == "Mutation" && typeChecker) continue
                for (queryFragment in listOf(false, true)) {
                    val failure = assertThrows<IllegalArgumentException> {
                        fun templates(schemas: ViaductAndGJSchema): Map<String, ResolverFragmentTemplates> =
                            mapOf(
                                "input" to ResolverFragmentTemplates(
                                    objectFragmentTemplate = if (queryFragment) materializeSelectionForestOf() else schemas.fragmentFrom("fragment Input on $owner { update }").materializeSelections,
                                    queryFragmentTemplate = if (queryFragment) schemas.fragmentFrom("fragment Input on Query { idle }").materializeSelections else materializeSelectionForestOf(),
                                ),
                            )
                        TestWorld.fromSDL(
                            """
                            directive @namespaceType on OBJECT
                            type Query { idle: Int }
                            type Mutation { update: Int, group: Group }
                            type Group @namespaceType { update: Int }
                            """.trimIndent(),
                            fieldCheckers = { schemas ->
                                val schema = schemas.loweredSchema
                                if (typeChecker) {
                                    emptyMap()
                                } else {
                                    val field = schema.requireObjectField(owner, "update")
                                    mapOf(field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef(), templates(schemas)) { _, _, _ -> CheckerResult.Success })
                                }
                            },
                            typeCheckers = { schemas ->
                                val schema = schemas.loweredSchema
                                if (!typeChecker) {
                                    emptyMap()
                                } else {
                                    val type = schema.requireType(owner) as ViaductSchema.Object
                                    mapOf(type to TypeCheckerResolver.of(type, schema.requireQueryTypeDef(), templates(schemas)) { _, _ -> CheckerResult.Success })
                                }
                            },
                        )
                    }
                    assertTrue(failure.message!!.contains("cannot declare object or Query fragments"))
                }
            }
        }
    }

    @Test
    fun `mutation root and namespace resolvers reject object and Query fragments`() {
        for (owner in listOf("Mutation", "Group")) {
            for (queryFragment in listOf(false, true)) {
                val failure = assertThrows<IllegalArgumentException> {
                    TestWorld.fromSDL(
                        """
                        directive @namespaceType on OBJECT
                        type Query { idle: Int }
                        type Mutation { update: Int, group: Group }
                        type Group @namespaceType { update: Int }
                        """.trimIndent(),
                        fieldResolvers = { schemas ->
                            val schema = schemas.loweredSchema
                            val definition: FieldResolverDefinition = if (queryFragment) {
                                fieldResolverOf(schema.emptyFragmentOf(owner), schemas.fragmentFrom("fragment Input on Query { idle }")) { _, _, _ -> 1 }
                            } else {
                                fieldResolverOf(schemas.fragmentFrom("fragment Input on $owner { update }")) { _, _ -> 1 }
                            }
                            mapOf(schema.requireObjectField(owner, "update") to definition)
                        },
                    )
                }
                assertTrue(failure.message!!.contains("cannot declare object or Query fragments; use ctx.query()"))
            }
        }
    }
}
