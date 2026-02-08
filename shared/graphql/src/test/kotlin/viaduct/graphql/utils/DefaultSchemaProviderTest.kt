@file:Suppress("DEPRECATION")

package viaduct.graphql.utils

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.OperationDefinition
import graphql.language.StringValue
import graphql.language.TypeName
import graphql.parser.MultiSourceReader
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import kotlin.io.path.writeText
import kotlin.jvm.optionals.getOrNull
import kotlin.jvm.optionals.toList
import kotlin.test.assertContains
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.utils.DefaultSchemaProvider.DefaultDirective

@Suppress("DEPRECATION")
class DefaultSchemaProviderTest {
    /**
     * High-level integration test for the public API. Ensures `addDefaults` adds default
     * schema components (directives, Node, scalars, root types) correctly from an empty registry.
     */
    @Test
    fun `addDefaults should add all default schema components`() {
        val sdl = "type Foo implements Node { id: ID! }" // Ensure creation of Node interface and Node query fields
        val registry = SchemaParser().parse(sdl)

        DefaultSchemaProvider.addDefaults(registry)

        // Verify all default directives are present
        defaultDirectiveNames().forEach { directiveName ->
            assertTrue(registry.getDirectiveDefinition(directiveName).isPresent, "Should have @$directiveName directive")
        }

        // Verify Node interface and structure
        assertTrue(registry.getType("Node").isPresent, "Should have Node interface")
        val nodeInterface = registry.getType("Node").get() as InterfaceTypeDefinition
        assertTrue(nodeInterface.name == "Node", "Should be named Node")
        assertNodeHasIdAndScopeAll(nodeInterface)

        // Verify all default scalars are present
        DefaultSchemaProvider.defaultScalars().forEach { scalar ->
            assertTrue(registry.getType(scalar.name).isPresent, "Should have ${scalar.name} scalar")
        }

        // Verify Query root type (always added)
        assertTrue(registry.getType("Query").isPresent, "Should have Query root type")

        // Verify Node query fields are added by default
        val queryExtensions = registry.objectTypeExtensions()["Query"] ?: emptyList()
        val nodeFields = queryExtensions.flatMap { it.fieldDefinitions }.filter { it.name == "node" || it.name == "nodes" }
        assertEquals(2, nodeFields.size, "Should have both node and nodes fields")
        assertTrue(registry.getType("Query").isPresent())

        val nodeField = nodeFields.first { it.name == "node" }
        assertEquals("Node", (nodeField.type as TypeName).name, "node field should return Node")
        assertTrue(nodeField.inputValueDefinitions.any { it.name == "id" }, "node field should have id argument")

        val nodesField = nodeFields.first { it.name == "nodes" }
        assertTrue(nodesField.type is NonNullType, "nodes field should return non-null")
        assertTrue(nodesField.inputValueDefinitions.any { it.name == "ids" }, "nodes field should have ids argument")

        // Verify Mutation and Subscription are not added without extensions
        assertFalse(registry.getType("Mutation").isPresent, "Should not have Mutation without extensions")
        assertFalse(registry.getType("Subscription").isPresent, "Should not have Subscription without extensions")

        // Verify schema definition wiring has only Query
        val schemaDefs = registry.schemaDefinition().toList()
        assertEquals(1, schemaDefs.size, "Should have exactly one SchemaDefinition")
        val ops = schemaDefs
            .single()
            .operationTypeDefinitions
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                OperationDefinition.Operation.QUERY.name
                    .lowercase()
            ),
            ops,
            "Should only wire QUERY op"
        )
    }

    @Test
    fun `addDefaults should prevent redefinition of standard scalars`() {
        val scalarName = deterministicScalarName()
        val sdl = "scalar $scalarName"
        val registry = SchemaParser().parse(sdl)

        val exception = assertThrows<RuntimeException> {
            DefaultSchemaProvider.addDefaults(registry)
        }

        assertAll(
            { assertContains(exception.message ?: "", "Standard Viaduct scalar", message = "Should mention standard scalar policy") },
            { assertContains(exception.message ?: "", scalarName, message = "Should mention specific scalar") },
            { assertContains(exception.message ?: "", "cannot be redefined", message = "Should prevent redefinition") }
        )
    }

    @Test
    fun `addDefaults should add all default directives (smoke)`() {
        val registry = TypeDefinitionRegistry()

        DefaultSchemaProvider.addDefaults(registry)

        // 1) All default directives are present
        defaultDirectiveNames().forEach { directiveName ->
            assertTrue(registry.getDirectiveDefinition(directiveName).isPresent, "Should have @$directiveName directive")
        }

        // 2) Each directive has at least one location (lightweight sanity)
        defaultDirectiveNames().forEach { name ->
            val def = registry.getDirectiveDefinition(name).get()
            assertTrue(def.directiveLocations.isNotEmpty(), "@$name should declare at least one location")
        }

        // 3) Single strong semantic invariant: @scope must carry 'to' argument (list-ish)
        val scope = registry.getDirectiveDefinition("scope").get()
        val toArg = scope.inputValueDefinitions.singleOrNull { it.name == "to" }
        assertTrue(toArg != null, "@scope must define 'to' argument")
        assertTrue(
            toArg!!.type is NonNullType || toArg.type is ListType,
            "'to' should be a list type (possibly non-null)"
        )
    }

    @Test
    fun `addDefaults should prevent redefinition of default directives`() {
        val directiveName = "resolver" // deterministic choice
        val sdl = "directive @$directiveName on FIELD_DEFINITION"
        val registry = SchemaParser().parse(sdl)

        val exception = assertThrows<RuntimeException> {
            DefaultSchemaProvider.addDefaults(registry)
        }

        assertAll(
            { assertContains(exception.message ?: "", "@$directiveName", message = "Should mention directive") },
            { assertContains(exception.message ?: "", "cannot be redefined", message = "Should prevent redefinition") }
        )
    }

    @Test
    fun `addDefaults should include connection directive with OBJECT location`() {
        // Given: An empty registry
        val registry = TypeDefinitionRegistry()

        // When: Adding defaults
        DefaultSchemaProvider.addDefaults(registry)

        // Then: @connection directive should be present with correct location
        val connectionDirective = registry.getDirectiveDefinition("connection").getOrNull()
        assertTrue(connectionDirective != null, "Should have @connection directive")
        assertEquals(
            listOf("OBJECT"),
            connectionDirective!!.directiveLocations.map { it.name },
            "@connection should only apply to OBJECT"
        )
    }

    @Test
    fun `addDefaults should include edge directive with OBJECT location`() {
        // Given: An empty registry
        val registry = TypeDefinitionRegistry()

        // When: Adding defaults
        DefaultSchemaProvider.addDefaults(registry)

        // Then: @edge directive should be present with correct location
        val edgeDirective = registry.getDirectiveDefinition("edge").getOrNull()
        assertTrue(edgeDirective != null, "Should have @edge directive")
        assertEquals(
            listOf("OBJECT"),
            edgeDirective!!.directiveLocations.map { it.name },
            "@edge should only apply to OBJECT"
        )
    }

    @Test
    fun `addDefaults should reject schema that redefines connection directive`() {
        // Given: A schema that defines @connection
        val sdl = """directive @connection on FIELD_DEFINITION"""
        val registry = SchemaParser().parse(sdl)

        // When/Then: Adding defaults should error
        val exception = assertThrows<RuntimeException> {
            DefaultSchemaProvider.addDefaults(registry)
        }
        assertContains(
            exception.message ?: "",
            "connection",
            message = "Error should mention the conflicting directive"
        )
    }

    @Test
    fun `addDefaults should reject schema that redefines edge directive`() {
        // Given: A schema that defines @edge
        val sdl = """directive @edge on FIELD_DEFINITION"""
        val registry = SchemaParser().parse(sdl)

        // When/Then: Adding defaults should error
        val exception = assertThrows<RuntimeException> {
            DefaultSchemaProvider.addDefaults(registry)
        }
        assertContains(
            exception.message ?: "",
            "edge",
            message = "Error should mention the conflicting directive"
        )
    }

    @Test
    fun `addDefaults should not add mutation, subscription types when no extensions exist`() {
        val sdl = """
            type User {
              name: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        DefaultSchemaProvider.addDefaults(registry)

        assertTrue(registry.getType("Query").isPresent, "Should add Query always")
        assertFalse(registry.getType("Mutation").isPresent, "Should not add Mutation without extensions")
        assertFalse(registry.getType("Subscription").isPresent, "Should not add Subscription without extensions")

        // Query should have dummy field when no extensions
        val query = registry.getType("Query").get() as ObjectTypeDefinition
        assertRootHasScopeAll(query)
        assertWiring(registry, expectMutation = false, expectSubscription = false)
    }

    @Test
    fun `addDefaults should add Query when Query extensions exist`() {
        val sdl = """
            type User {
              name: String
            }

            extend type Query {
              users: [User]
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        DefaultSchemaProvider.addDefaults(registry)

        assertTrue(registry.getType("Query").isPresent, "Should add Query when extensions exist")
        val query = registry.getType("Query").get() as ObjectTypeDefinition
        assertRootHasScopeAll(query)
        assertFalse(registry.getType("Mutation").isPresent, "Should not add Mutation without extensions")
        assertFalse(registry.getType("Subscription").isPresent, "Should not add Subscription without extensions")
        assertWiring(registry, expectMutation = false, expectSubscription = false)
    }

    @Test
    fun `addDefaults should add all root types when all have extensions`() {
        val sdl = """
            type User {
              name: String
            }

            extend type Query {
              users: [User]
            }

            extend type Mutation {
              createUser(name: String!): User
            }

            extend type Subscription {
              userUpdated: User
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        DefaultSchemaProvider.addDefaults(registry)
        val query = registry.getType("Query").get() as ObjectTypeDefinition
        val mutation = registry.getType("Mutation").get() as ObjectTypeDefinition
        val subscription = registry.getType("Subscription").get() as ObjectTypeDefinition

        assertRootHasScopeAll(query)
        assertRootHasScopeAll(mutation)
        assertRootHasScopeAll(subscription)
        assertWiring(registry, expectMutation = true, expectSubscription = true)
    }

    @Test
    fun `addDefaults should never add Node interface when includeNodeDefinition is Never`() {
        val registry = SchemaParser()
            .parse("type User implements Node { id:ID! }")
            .also {
                DefaultSchemaProvider.addDefaults(
                    it,
                    includeNodeDefinition = DefaultSchemaProvider.IncludeNodeSchema.Never,
                    includeNodeQueries = DefaultSchemaProvider.IncludeNodeSchema.Never
                )
            }
        assertNodeSchema(registry, expectNode = false)
    }

    @Test
    fun `addDefaults should always add Node interface when includeNodeDefinition is Always`() {
        val registry = TypeDefinitionRegistry()
            .also {
                DefaultSchemaProvider.addDefaults(it, includeNodeDefinition = DefaultSchemaProvider.IncludeNodeSchema.Always)
            }
        assertNodeSchema(registry, expectNode = true)
    }

    @Test
    fun `addDefaults should not add Node interface when there are no implementations of Node`() {
        val registry = TypeDefinitionRegistry().also(DefaultSchemaProvider::addDefaults)
        assertNodeSchema(registry, expectNode = false)
    }

    @Test
    fun `addDefaults should add Node interface when Node implementing type exists`() {
        val registry = SchemaParser()
            .parse("type User implements Node { id:ID! }")
            .also(DefaultSchemaProvider::addDefaults)
        assertNodeSchema(registry, expectNode = true)
    }

    @Test
    fun `addDefaults should add Node interface when Node implementing object extension exists`() {
        val registry = SchemaParser()
            .parse(
                """
                    type User { empty:Int }
                    extend type User implements Node { id:ID! }
                """.trimIndent()
            ).also(DefaultSchemaProvider::addDefaults)

        // Verify Node interface is added
        assertNodeSchema(registry, expectNode = true)
    }

    @Test
    fun `addDefaults should add Node interface when Node implementing interface exists`() {
        val registry = SchemaParser()
            .parse("interface I implements Node { empty:Int }")
            .also(DefaultSchemaProvider::addDefaults)

        // Verify Node interface is added
        assertNodeSchema(registry, expectNode = true)
    }

    @Test
    fun `addDefaults should add Node interface when Node implementing interface extension exists`() {
        val registry = SchemaParser()
            .parse(
                """
                interface I { empty:Int }
                extend interface I implements Node { id:ID! }
                """.trimIndent()
            ).also(DefaultSchemaProvider::addDefaults)

        // Verify Node interface is added
        assertNodeSchema(registry, expectNode = true)
    }

    @Test
    fun `addDefaults should add Node interface when Object field returns Node`() {
        val registry = SchemaParser()
            .parse("type O { nodes:[Node!]! }")
            .also(DefaultSchemaProvider::addDefaults)

        // Verify Node interface is added
        assertNodeSchema(registry, expectNode = true)
    }

    @Test
    fun `addDefaults should add Node interface when Object extension field returns Node`() {
        val registry = SchemaParser()
            .parse("extend type Query { x:Node }")
            .also(DefaultSchemaProvider::addDefaults)

        // Verify Node interface is added
        assertNodeSchema(registry, expectNode = true)
    }

    @Test
    fun `addDefaults should add Node interface when Interface extension field returns Node`() {
        val registry = SchemaParser()
            .parse(
                """
                interface I { empty:Int }
                extend type I { node:Node }
                """.trimIndent()
            ).also(DefaultSchemaProvider::addDefaults)

        // Verify Node interface is added
        assertNodeSchema(registry, expectNode = true)
    }

    @Test
    fun `addDefaults should not add Node query fields when includeNodeQueries is Never`() {
        val sdl = "type User implements Node { id:ID! }"
        val registry = SchemaParser().parse(sdl)

        DefaultSchemaProvider.addDefaults(registry, includeNodeQueries = DefaultSchemaProvider.IncludeNodeSchema.Never)

        // Verify Node interface is still present
        assertTrue(registry.getType("Node").isPresent, "Should have Node interface")

        // Verify Node query fields are NOT added
        val queryExtensions = registry.objectTypeExtensions()["Query"] ?: emptyList()
        val nodeFields = queryExtensions.flatMap { it.fieldDefinitions }.filter { it.name == "node" || it.name == "nodes" }
        assertEquals(0, nodeFields.size, "Should not have node or nodes fields when includeNodeQueries=false")
    }

    @Test
    fun `addDefaults should always add Node query fields when includeNodeQueries=Always`() {
        val registry = TypeDefinitionRegistry().also {
            DefaultSchemaProvider.addDefaults(it, includeNodeQueries = DefaultSchemaProvider.IncludeNodeSchema.Always)
        }
        assertNodeSchema(registry, true)
    }

    @Test
    fun `addDefaults should prevent redefinition of Node query fields`() {
        val sdl = """
            extend type Query {
              node(id: ID!): Node
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        val exception = assertThrows<RuntimeException> {
            DefaultSchemaProvider.addDefaults(registry)
        }

        assertAll(
            { assertContains(exception.message ?: "", "Node query fields", message = "Should mention Node query fields") },
            { assertContains(exception.message ?: "", "cannot be redefined", message = "Should prevent redefinition") }
        )
    }

    @Test
    fun `addDefaults with allowExisting=true should allow existing Node query fields`() {
        val sdl = """
            extend type Query {
              node(id: ID!): Node
              nodes(ids: [ID!]!): [Node]!
            }
            type Foo implements Node {
              id: ID!
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        // Should not throw
        DefaultSchemaProvider.addDefaults(registry, allowExisting = true)

        // Verify Node query fields are still present
        val queryExtensions = registry.objectTypeExtensions()["Query"] ?: emptyList()
        val nodeFields = queryExtensions.flatMap { it.fieldDefinitions }.filter { it.name == "node" || it.name == "nodes" }
        assertTrue(nodeFields.isNotEmpty(), "Should preserve existing Node query fields")
    }

    @Test
    fun `addDefaults should error when root type definition conflicts with extensions`() {
        val sdl = """
            type Query {
              existingField: String
            }

            extend type Query {
              extendedField: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        val exception = assertThrows<RuntimeException> { DefaultSchemaProvider.addDefaults(registry) }

        assertAll(
            { assertContains(exception.message ?: "", "Root type Query", message = "Should mention Query root") },
            { assertContains(exception.message ?: "", "cannot be manually defined", message = "Should forbid manual def") }
        )
    }

    @Test
    fun `addDefaults should error when any root type is manually defined even without extensions`() {
        val sdl = """
            type Query {
              existingField: String
            }

            type User {
              name: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        val exception = assertThrows<RuntimeException> { DefaultSchemaProvider.addDefaults(registry) }

        assertAll(
            { assertContains(exception.message ?: "", "Root type Query", message = "Should mention Query root") },
            { assertContains(exception.message ?: "", "cannot be manually defined", message = "Should forbid manual def") }
        )
    }

    @Test
    fun `addDefaults should error when Mutation type is manually defined`() {
        val sdl = """
            type Mutation {
              createUser: String
            }

            type User {
              name: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        val exception = assertThrows<RuntimeException> { DefaultSchemaProvider.addDefaults(registry) }

        assertAll(
            { assertContains(exception.message ?: "", "Root type Mutation", message = "Should mention Mutation root") },
            { assertContains(exception.message ?: "", "cannot be manually defined", message = "Should forbid manual def") }
        )
    }

    @Test
    fun `addDefaults should allow Subscription extension`() {
        val sdl = """
            extend type Subscription {
              userUpdated: String
            }

            type User {
              name: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        assertDoesNotThrow { DefaultSchemaProvider.addDefaults(registry) }
    }

    @Test
    fun `addDefaults should allow Subscription extension when airbnbModeEnabled is true`() {
        val sdl = """
            extend type Subscription {
              userUpdated: String
            }

            type User {
              name: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        assertDoesNotThrow { DefaultSchemaProvider.addDefaults(registry) }
        assertTrue(registry.getType("Subscription").isPresent)
    }

    @Test
    fun `addDefaults should error when Node interface already exists unless allowExisting`() {
        val sdl = """
            interface Node { customId: String }
            type User implements Node { customId: String }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        val exception = assertThrows<RuntimeException> { DefaultSchemaProvider.addDefaults(registry) }

        assertAll(
            { assertContains(exception.message ?: "", "Node interface", message = "Should mention Node interface") },
            { assertContains(exception.message ?: "", "cannot be redefined", message = "Should prevent redefinition") }
        )
    }

    @Test
    fun `addDefaults with allowExisting=true should allow all existing default components`() {
        val sampleDirective = "resolver" // deterministic
        val sampleScalar = deterministicScalarName()

        val sdl = """
            directive @$sampleDirective on FIELD_DEFINITION
            scalar $sampleScalar
            interface Node { id: ID! }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        // Should not throw with allowExisting=true
        DefaultSchemaProvider.addDefaults(registry, allowExisting = true)

        // Verify all component types are present
        assertTrue(registry.getDirectiveDefinition(sampleDirective).isPresent, "Should preserve existing directive")
        assertTrue(registry.getType(sampleScalar).isPresent, "Should preserve existing scalar")
        assertTrue(registry.getType("Node").isPresent, "Should preserve existing Node interface")

        // Verify all default components were added
        defaultDirectiveNames().forEach { directiveName ->
            assertTrue(registry.getDirectiveDefinition(directiveName).isPresent, "Should have @$directiveName directive")
        }
        DefaultSchemaProvider.defaultScalars().forEach { scalar ->
            assertTrue(registry.getType(scalar.name).isPresent, "Should have ${scalar.name} scalar")
        }
    }

    @Test
    fun `addDefaults with allowExisting=true should not error when Node interface already exists`() {
        val sdl = """
            interface Node {
              customId: String!
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        // Should not throw
        DefaultSchemaProvider.addDefaults(registry, allowExisting = true)

        // Node interface should still be present and preserve user field
        assertTrue(registry.getType("Node").isPresent)
        val node = registry.getType("Node").get() as InterfaceTypeDefinition
        assertTrue(node.fieldDefinitions.any { it.name == "customId" }, "Should preserve existing Node fields")
    }

    @Test
    fun `addDefaults with allowExisting=true should not error when root types already exist`() {
        val sdl = """
            type Query {
              existingField: String
            }

            type Mutation {
              existingMutation: String
            }

            type Subscription {
              existingSubscription: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        // Should not throw
        DefaultSchemaProvider.addDefaults(registry, forceAddRootTypes = true, allowExisting = true)

        // Root types should still be present (existing ones)
        assertTrue(registry.getType("Query").isPresent)
        assertTrue(registry.getType("Mutation").isPresent)
        assertTrue(registry.getType("Subscription").isPresent)
    }

    @Test
    fun `addDefaults is idempotent and keeps single SchemaDefinition`() {
        val registry = TypeDefinitionRegistry()

        DefaultSchemaProvider.addDefaults(registry, forceAddRootTypes = true)
        DefaultSchemaProvider.addDefaults(registry, forceAddRootTypes = true, allowExisting = true)

        val schemaDefs = registry.schemaDefinition().toList()
        assertEquals(1, schemaDefs.size, "Should keep exactly one SchemaDefinition after repeated calls")
    }

    @Test
    fun `getDefaultSDL should round trip essential structure`() {
        val sdl = DefaultSchemaProvider.getDefaultSDL()
        val registry = SchemaParser().parse(sdl)

        // Directives exist
        defaultDirectiveNames().forEach { name ->
            assertTrue(registry.getDirectiveDefinition(name).isPresent, "Should include @$name in SDL")
        }
        // At least Query exists (do not over-constrain Mutation/Subscription)
        assertTrue(registry.getType("Query").isPresent, "SDL should include Query")
    }

    @Test
    fun `getDefaultSDL should not include node schema with empty extants`() {
        val sdl = DefaultSchemaProvider.getDefaultSDL(
            existingSDLFiles = listOf()
        )

        val registry = SchemaParser().parse(sdl)
        assertNodeSchema(registry, false)
    }

    @Test
    fun `getDefaultSDL should include node schema if used in extant files`() {
        val sdl = DefaultSchemaProvider.getDefaultSDL(
            existingSDLFiles = listOf(
                mkFile("type User implements Node { id:ID! }")
            )
        )

        val registry = SchemaParser().parse(sdl)
        assertNodeSchema(registry, true)
    }

    @Test
    fun `getDefaultSDL should include query definition with empty extants`() {
        val sdl = DefaultSchemaProvider.getDefaultSDL(
            existingSDLFiles = emptyList()
        )

        val registry = SchemaParser().parse(sdl)
        assertTrue(registry.getType("Query").isPresent)
    }

    @Test
    fun `getDefaultSDL should include query definition when extended in extant files`() {
        val sdl = DefaultSchemaProvider.getDefaultSDL(
            existingSDLFiles = listOf(
                mkFile("extend type Query { x:Int }")
            )
        )

        val registry = SchemaParser().parse(sdl)
        assertTrue(registry.getType("Query").isPresent)
    }

    @Test
    fun `getDefaultSDL can be used to compose a valid schema with extant files`() {
        val extant = mkFile("extend type Query { x:Int }")
        val default = mkFile(DefaultSchemaProvider.getDefaultSDL(existingSDLFiles = listOf(extant)))

        val reader = MultiSourceReader
            .newMultiSourceReader()
            .reader(extant.reader(), extant.path)
            .reader(default.reader(), default.path)
            .build()

        val tdr = SchemaParser().parse(reader)
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(tdr)
        assertEquals(listOf("x"), schema.queryType.fieldDefinitions.map { it.name })
    }

    @Test
    fun `getDefaultSDL should include PageInfo type by default`() {
        val sdl = DefaultSchemaProvider.getDefaultSDL()

        assertTrue(sdl.contains("type PageInfo"), "Generated default SDL should include PageInfo")
    }

    @Test
    fun `getDefaultSDL should include PageInfo type when includePageInfo is true`() {
        val sdl = DefaultSchemaProvider.getDefaultSDL(includePageInfo = true)

        assertTrue(sdl.contains("type PageInfo"), "Generated default SDL should include PageInfo when requested")
    }

    @Test
    fun `addDefaults should add PageInfo type when not present`() {
        val registry = TypeDefinitionRegistry()

        DefaultSchemaProvider.addDefaults(registry)

        assertTrue(registry.getType("PageInfo").isPresent, "Should have PageInfo type")
        val pageInfo = registry.getType("PageInfo").get() as ObjectTypeDefinition
        assertEquals("PageInfo", pageInfo.name)
    }

    @Test
    fun `addDefaults should create PageInfo with correct Relay fields`() {
        val registry = TypeDefinitionRegistry()

        DefaultSchemaProvider.addDefaults(registry)

        val pageInfo = registry.getType("PageInfo").get() as ObjectTypeDefinition

        // Verify all required fields
        val fields = pageInfo.fieldDefinitions.associateBy { it.name }
        assertTrue(fields.containsKey("hasNextPage"), "Should have hasNextPage field")
        assertTrue(fields.containsKey("hasPreviousPage"), "Should have hasPreviousPage field")
        assertTrue(fields.containsKey("startCursor"), "Should have startCursor field")
        assertTrue(fields.containsKey("endCursor"), "Should have endCursor field")
    }

    @Test
    fun `addDefaults should create PageInfo with correct field nullability`() {
        val registry = TypeDefinitionRegistry()

        DefaultSchemaProvider.addDefaults(registry)

        val pageInfo = registry.getType("PageInfo").get() as ObjectTypeDefinition
        val fields = pageInfo.fieldDefinitions.associateBy { it.name }

        assertTrue(
            fields["hasNextPage"]!!.type is NonNullType,
            "hasNextPage should be non-nullable"
        )
        assertTrue(
            fields["hasPreviousPage"]!!.type is NonNullType,
            "hasPreviousPage should be non-nullable"
        )

        assertFalse(
            fields["startCursor"]!!.type is NonNullType,
            "startCursor should be nullable"
        )
        assertFalse(
            fields["endCursor"]!!.type is NonNullType,
            "endCursor should be nullable"
        )
    }

    @Test
    fun `addDefaults should create PageInfo with scope directive`() {
        val registry = TypeDefinitionRegistry()

        DefaultSchemaProvider.addDefaults(registry)

        val pageInfo = registry.getType("PageInfo").get() as ObjectTypeDefinition
        val scope = pageInfo.directives.singleOrNull { it.name == "scope" }
        assertTrue(scope != null, "PageInfo should have @scope directive")
        assertScopeToAll(scope!!)
    }

    @Test
    fun `addDefaults should validate non-conforming PageInfo and throw error`() {
        val nonConformingPageInfoSdl = "type PageInfo { customField: String }"
        val registry = SchemaParser().parse(nonConformingPageInfoSdl)

        val exception = assertThrows<RuntimeException> {
            DefaultSchemaProvider.addDefaults(registry)
        }

        assertAll(
            { assertContains(exception.message ?: "", "PageInfo", message = "Should mention PageInfo") },
            { assertContains(exception.message ?: "", "does not conform to Relay", message = "Should explain non-conformance") }
        )
    }

    @Test
    fun `addDefaults with allowExisting=true should preserve existing PageInfo`() {
        val sdl = """
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
                extraField: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(sdl)

        DefaultSchemaProvider.addDefaults(registry, allowExisting = true)

        // Verify existing PageInfo is preserved with extra field
        val pageInfo = registry.getType("PageInfo").get() as ObjectTypeDefinition
        val fields = pageInfo.fieldDefinitions.map { it.name }
        assertTrue(fields.contains("extraField"), "Should preserve extra field from user's PageInfo")
        assertEquals(5, fields.size, "Should preserve all fields from user's PageInfo")
    }

    @Test
    fun `addDefaults with allowExisting should accept PageInfo with exact Relay fields`() {
        val relayCompliantPageInfoSdl = """
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(relayCompliantPageInfoSdl)

        assertDoesNotThrow {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }
    }

    @Test
    fun `addDefaults with allowExisting should accept PageInfo implementing IPageInfo`() {
        val airbnbIPageInfoSdl = """
            interface IPageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type PageInfo implements IPageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
                totalCount: Int
            }
        """.trimIndent()
        val registry = SchemaParser().parse(airbnbIPageInfoSdl)

        assertDoesNotThrow {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }
    }

    @Test
    fun `addDefaults with allowExisting should reject PageInfo missing hasNextPage field`() {
        val pageInfoMissingHasNextPage = """
            type PageInfo {
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoMissingHasNextPage)

        val exception = assertThrows<IllegalStateException> {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }

        assertAll(
            { assertContains(exception.message ?: "", "PageInfo", message = "Should mention PageInfo") },
            { assertContains(exception.message ?: "", "hasNextPage", message = "Should mention missing field") },
            { assertContains(exception.message ?: "", "Missing required field", message = "Should indicate field is missing") }
        )
    }

    @Test
    fun `addDefaults with allowExisting should reject PageInfo missing hasPreviousPage field`() {
        val pageInfoMissingHasPreviousPage = """
            type PageInfo {
                hasNextPage: Boolean!
                startCursor: String
                endCursor: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoMissingHasPreviousPage)

        val exception = assertThrows<IllegalStateException> {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }

        assertContains(exception.message ?: "", "hasPreviousPage", message = "Should mention missing field")
    }

    @Test
    fun `addDefaults with allowExisting should reject PageInfo missing startCursor field`() {
        val pageInfoMissingStartCursor = """
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                endCursor: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoMissingStartCursor)

        val exception = assertThrows<IllegalStateException> {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }

        assertContains(exception.message ?: "", "startCursor", message = "Should mention missing field")
    }

    @Test
    fun `addDefaults with allowExisting should reject PageInfo missing endCursor field`() {
        val pageInfoMissingEndCursor = """
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoMissingEndCursor)

        val exception = assertThrows<IllegalStateException> {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }

        assertContains(exception.message ?: "", "endCursor", message = "Should mention missing field")
    }

    @Test
    fun `addDefaults with allowExisting should reject PageInfo with nullable hasNextPage`() {
        val pageInfoWithNullableHasNextPage = """
            type PageInfo {
                hasNextPage: Boolean
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoWithNullableHasNextPage)

        val exception = assertThrows<IllegalStateException> {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }

        assertAll(
            { assertContains(exception.message ?: "", "hasNextPage", message = "Should mention the field") },
            { assertContains(exception.message ?: "", "non-nullable", message = "Should indicate nullability issue") }
        )
    }

    @Test
    fun `addDefaults with allowExisting should reject PageInfo with nullable hasPreviousPage`() {
        val pageInfoWithNullableHasPreviousPage = """
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean
                startCursor: String
                endCursor: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoWithNullableHasPreviousPage)

        val exception = assertThrows<IllegalStateException> {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }

        assertAll(
            { assertContains(exception.message ?: "", "hasPreviousPage", message = "Should mention the field") },
            { assertContains(exception.message ?: "", "non-nullable", message = "Should indicate nullability issue") }
        )
    }

    @Test
    fun `addDefaults with allowExisting should reject PageInfo with wrong cursor type`() {
        val pageInfoWithNonStringCursors = """
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: Int
                endCursor: Int
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoWithNonStringCursors)

        val exception = assertThrows<IllegalStateException> {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }

        assertAll(
            { assertContains(exception.message ?: "", "startCursor", message = "Should mention startCursor") },
            { assertContains(exception.message ?: "", "String", message = "Should mention expected type") }
        )
    }

    @Test
    fun `addDefaults with allowExisting should list all validation errors`() {
        val pageInfoWithMultipleValidationErrors = """
            type PageInfo {
                hasNextPage: Boolean
                customField: String
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoWithMultipleValidationErrors)

        val exception = assertThrows<IllegalStateException> {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }

        val message = exception.message ?: ""
        assertAll(
            { assertContains(message, "hasNextPage", message = "Should mention nullable hasNextPage") },
            { assertContains(message, "hasPreviousPage", message = "Should mention missing hasPreviousPage") },
            { assertContains(message, "startCursor", message = "Should mention missing startCursor") },
            { assertContains(message, "endCursor", message = "Should mention missing endCursor") }
        )
    }

    @Test
    fun `addDefaults with allowExisting should accept PageInfo with non-nullable cursors`() {
        val pageInfoWithNonNullableCursors = """
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String!
                endCursor: String!
            }
        """.trimIndent()
        val registry = SchemaParser().parse(pageInfoWithNonNullableCursors)

        // Should accept (non-nullable is stricter, which is fine)
        assertDoesNotThrow {
            DefaultSchemaProvider.addDefaults(registry, allowExisting = true)
        }
    }

    // ---- helpers ----

    private fun defaultDirectiveNames() = DefaultDirective.values().map { it.directiveName }

    private fun deterministicScalarName(): String =
        DefaultSchemaProvider.defaultScalars().minOfOrNull { it.name }
            ?: error("No default scalars registered")

    private fun assertNodeHasIdAndScopeAll(node: InterfaceTypeDefinition) {
        val id = node.fieldDefinitions.singleOrNull { it.name == "id" }
        assertTrue(id != null, "Node should have 'id' field")
        assertTrue(id!!.type is NonNullType, "id should be NonNullType")
        val inner = (id.type as NonNullType).type
        assertTrue(inner is TypeName && inner.name == "ID", "id should be ID!")

        val scope = node.directives.singleOrNull { it.name == "scope" }
        assertTrue(scope != null, "Node should have @scope")
        assertScopeToAll(scope!!)
    }

    private fun assertRootHasScopeAll(obj: ObjectTypeDefinition) {
        val scope = obj.directives.singleOrNull { it.name == "scope" }
        assertTrue(scope != null, "Root type should have @scope")
        assertScopeToAll(scope!!)
    }

    private fun assertScopeToAll(scope: Directive) {
        val toArg = scope.arguments.singleOrNull { it.name == "to" }
        assertTrue(toArg != null, "@scope should have 'to' argument")
        val arr = toArg!!.value as ArrayValue
        val values = arr.values.map { (it as StringValue).value }
        assertEquals(listOf("*"), values, "'to' should be [\"*\"]")
    }

    private fun assertNodeSchema(
        registry: TypeDefinitionRegistry,
        expectNode: Boolean
    ) {
        val nodeDef = registry.getType("Node")
        assertEquals(expectNode, nodeDef.isPresent)

        val baseFields = registry.getType("Query", ObjectTypeDefinition::class.java).getOrNull()?.fieldDefinitions
        val extFields = registry.objectTypeExtensions()["Query"]?.flatMap { it.fieldDefinitions }
        val allFields = listOf(baseFields ?: emptyList(), extFields ?: emptyList()).flatten()

        val queryNodeFields = allFields.filter { it.name == "node" || it.name == "nodes" }
        val expQueryNodeFields = if (expectNode) 2 else 0

        assertEquals(expQueryNodeFields, queryNodeFields.size)
    }

    private fun assertWiring(
        registry: TypeDefinitionRegistry,
        expectMutation: Boolean,
        expectSubscription: Boolean
    ) {
        val schemaDefs = registry.schemaDefinition().toList()
        assertEquals(1, schemaDefs.size, "Should have exactly one SchemaDefinition")
        val ops = schemaDefs.single().operationTypeDefinitions.associateBy { it.name }
        assertTrue(
            ops.containsKey(
                OperationDefinition.Operation.QUERY.name
                    .lowercase()
            ),
            "Should wire QUERY"
        )
        assertEquals(
            expectMutation,
            ops.containsKey(
                OperationDefinition.Operation.MUTATION.name
                    .lowercase()
            ),
            "Mutation wiring presence mismatch"
        )
        assertEquals(
            expectSubscription,
            ops.containsKey(
                OperationDefinition.Operation.SUBSCRIPTION.name
                    .lowercase()
            ),
            "Subscription wiring presence mismatch"
        )
    }

    private fun mkFile(body: String): File =
        kotlin.io.path
            .createTempFile()
            .also {
                it.writeText(body)
            }.toFile()
}
