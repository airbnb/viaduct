package viaduct.graphql.schema.cli

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.test.mkSchema

class RootsIteratorTest {
    companion object {
        // Singleton directive needed for root detection
        private const val SINGLETON_DIRECTIVE = "directive @singleton on OBJECT"
    }

    // ========== rootTypeDef() tests ==========

    @Test
    fun `rootTypeDef returns Query type for QUERY`() {
        val schema = mkSchema(SINGLETON_DIRECTIVE)
        val queryDef = schema.rootTypeDef(RootTypeEnum.QUERY)
        assertEquals("Query", queryDef?.name)
    }

    @Test
    fun `rootTypeDef returns Mutation type for MUTATION`() {
        val schema = mkSchema(SINGLETON_DIRECTIVE)
        val mutationDef = schema.rootTypeDef(RootTypeEnum.MUTATION)
        assertEquals("Mutation", mutationDef?.name)
    }

    @Test
    fun `rootTypeDef returns null for SUBSCRIPTION when not defined`() {
        val schema = mkSchema(SINGLETON_DIRECTIVE)
        val subscriptionDef = schema.rootTypeDef(RootTypeEnum.SUBSCRIPTION)
        assertEquals(null, subscriptionDef)
    }

    // ========== roots() iterator tests ==========

    @Test
    fun `singleton directive is properly recognized`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type Viewer @singleton {
                user: User
            }
            type User {
                id: ID
            }
            extend type Query {
                viewer: Viewer
            }
            """.trimIndent()
        )

        val viewerDef = schema.types["Viewer"]!!
        assertTrue(viewerDef.hasAppliedDirective("singleton"), "Viewer should have @singleton directive")

        val userDef = schema.types["User"]!!
        assertFalse(userDef.hasAppliedDirective("singleton"), "User should NOT have @singleton directive")
    }

    @Test
    fun `roots returns direct fields when no singletons`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type User {
                id: ID
                name: String
            }
            extend type Query {
                user: User
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("user")), "Should contain direct user root")
    }

    @Test
    fun `roots traverses one level of singleton`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type Viewer @singleton {
                user: User
            }
            type User {
                id: ID
                name: String
            }
            extend type Query {
                viewer: Viewer
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("viewer", "user")), "Should contain nested user root through viewer")
        assertFalse(roots.any { it == listOf("viewer") }, "viewer itself should not be a root (it's a singleton)")
    }

    @Test
    fun `roots traverses two levels of singletons`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type Viewer @singleton {
                auth: Auth
            }
            type Auth @singleton {
                user: User
            }
            type User {
                id: ID
            }
            extend type Query {
                viewer: Viewer
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("viewer", "auth", "user")), "Should traverse through two singletons")
    }

    @Test
    fun `roots returns multiple roots at same singleton level`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type Viewer @singleton {
                user: User
                listing: Listing
            }
            type User {
                id: ID
            }
            type Listing {
                id: ID
            }
            extend type Query {
                viewer: Viewer
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("viewer", "user")), "Should contain user root")
        assertTrue(roots.contains(listOf("viewer", "listing")), "Should contain listing root")
    }

    @Test
    fun `roots handles mixed singleton and non-singleton fields`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type Viewer @singleton {
                nested: Listing
            }
            type User {
                id: ID
            }
            type Listing {
                id: ID
            }
            extend type Query {
                direct: User
                viewer: Viewer
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("direct")), "Should contain direct root")
        assertTrue(roots.contains(listOf("viewer", "nested")), "Should contain nested root through singleton")
    }

    @Test
    fun `roots skips Query self-reference`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type Viewer @singleton {
                query: Query
                user: User
            }
            type User {
                id: ID
            }
            extend type Query {
                viewer: Viewer
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("viewer", "user")), "Should contain user root")
        assertFalse(roots.any { it.contains("query") }, "Should not contain query self-reference")
    }

    @Test
    fun `roots skips Viewer self-reference`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type Viewer @singleton {
                viewer: Viewer
                user: User
            }
            type User {
                id: ID
            }
            extend type Query {
                viewer: Viewer
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("viewer", "user")), "Should contain user root")
        // The path ["viewer", "viewer"] should not cause infinite recursion
        // Note: there's also "nop" from the base schema, so we expect 2 roots
        assertEquals(2, roots.size, "Should have two roots (nop and viewer->user)")
    }

    @Test
    fun `roots returns empty iterator when root type is null`() {
        val schema = mkSchema(SINGLETON_DIRECTIVE)

        val roots = schema.roots(RootTypeEnum.SUBSCRIPTION).asSequence().toList()

        assertTrue(roots.isEmpty(), "Should return empty for non-existent root type")
    }

    @Test
    fun `roots works with Mutation type`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type User {
                id: ID
            }
            extend type Mutation {
                createUser: User
                deleteUser: User
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.MUTATION).asSequence().toList()

        assertTrue(roots.contains(listOf("createUser")), "Should contain createUser mutation")
        assertTrue(roots.contains(listOf("deleteUser")), "Should contain deleteUser mutation")
    }

    @Test
    fun `roots handles singleton with no fields gracefully`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type EmptySingleton @singleton {
                placeholder: String
            }
            extend type Query {
                empty: EmptySingleton
            }
            """.trimIndent()
        )

        // String is a scalar, so it should be treated as a root
        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("empty", "placeholder")), "Should contain scalar field as root")
    }

    @Test
    fun `roots throws on recursive singleton cycle`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type A @singleton {
                b: B
            }
            type B @singleton {
                a: A
            }
            extend type Query {
                a: A
            }
            """.trimIndent()
        )

        assertThrows<IllegalArgumentException> {
            schema.roots(RootTypeEnum.QUERY).asSequence().toList()
        }
    }

    @Test
    fun `roots handles interface type fields correctly`() {
        // Interface types are not Records, so traversal should stop at them
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            interface Node {
                id: ID
            }
            type Viewer @singleton {
                node: Node
            }
            extend type Query {
                viewer: Viewer
            }
            """.trimIndent()
        )

        val roots = schema.roots(RootTypeEnum.QUERY).asSequence().toList()

        assertTrue(roots.contains(listOf("viewer", "node")), "Interface field should be a root")
    }

    // ========== collectAllRootFields() tests ==========

    @Test
    fun `collectAllRootFields returns fields from Query`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type User {
                id: ID
            }
            extend type Query {
                user: User
            }
            """.trimIndent()
        )

        val rootFields = schema.collectAllRootFields()

        val fieldNames = rootFields.map { it.name }
        assertTrue(fieldNames.contains("user"), "Should contain user field")
    }

    @Test
    fun `collectAllRootFields returns fields from Query and Mutation`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type User {
                id: ID
            }
            extend type Query {
                user: User
            }
            extend type Mutation {
                createUser: User
            }
            """.trimIndent()
        )

        val rootFields = schema.collectAllRootFields()

        val fieldNames = rootFields.map { it.name }
        assertTrue(fieldNames.contains("user"), "Should contain user from Query")
        assertTrue(fieldNames.contains("createUser"), "Should contain createUser from Mutation")
    }

    @Test
    fun `collectAllRootFields returns nested fields through singletons`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type Viewer @singleton {
                user: User
            }
            type User {
                id: ID
            }
            extend type Query {
                viewer: Viewer
            }
            """.trimIndent()
        )

        val rootFields = schema.collectAllRootFields()

        val fieldNames = rootFields.map { it.name }
        assertTrue(fieldNames.contains("user"), "Should contain nested user field")
        assertFalse(fieldNames.contains("viewer"), "Should not contain singleton field itself")
    }

    @Test
    fun `collectAllRootFields returns actual Field objects`() {
        val schema = mkSchema(
            """
            $SINGLETON_DIRECTIVE
            type User {
                id: ID
                name: String
            }
            extend type Query {
                user: User
            }
            """.trimIndent()
        )

        val rootFields = schema.collectAllRootFields()

        // Verify we got actual Field objects with correct containing type
        val userField = rootFields.find { it.name == "user" }
        assertTrue(userField != null, "Should find user field")
        assertEquals("Query", userField.containingDef.name, "user field should belong to Query")
    }

    @Test
    fun `collectAllRootFields handles empty schema gracefully`() {
        val schema = mkSchema(SINGLETON_DIRECTIVE)

        val rootFields = schema.collectAllRootFields()

        // Should only contain the default nop field from mkSchema
        assertTrue(rootFields.size <= 2, "Should have minimal fields from base schema")
    }
}
