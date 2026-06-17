@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ResolvedEngineObjectData

class ObjectRootFieldReferenceTest {
    @Test
    fun `buildPathSelectionString nests fields with no args`() {
        val result = ObjectRootFieldReference.buildPathSelectionString(
            listOf("_factories", "ugcText", "create"),
            emptyMap(),
            "name price"
        )
        assertEquals("_factories { ugcText { create { name price } } }", result)
    }

    @Test
    fun `buildPathSelectionString with args uses variable references`() {
        val result = ObjectRootFieldReference.buildPathSelectionString(
            listOf("_factories", "ugcText", "create"),
            mapOf("sourceText" to "hello", "locale" to "en"),
            "name price"
        )
        assertEquals("_factories { ugcText { create(sourceText: \$__rfr_sourceText, locale: \$__rfr_locale) { name price } } }", result)
    }

    @Test
    fun `buildPathSelectionString with single field and args`() {
        val result = ObjectRootFieldReference.buildPathSelectionString(
            listOf("create"),
            mapOf("input" to "test"),
            "name"
        )
        assertEquals("create(input: \$__rfr_input) { name }", result)
    }

    @Test
    fun `extractNestedResult walks path to leaf`(): Unit =
        runTest {
            val leafType = GraphQLObjectType.newObject().name("Leaf").build()
            val leaf = ResolvedEngineObjectData.Builder(leafType).put("value", 42).build()

            val midType = GraphQLObjectType.newObject().name("Mid").build()
            val mid = ResolvedEngineObjectData.Builder(midType).put("inner", leaf).build()

            val rootType = GraphQLObjectType.newObject().name("Root").build()
            val root = ResolvedEngineObjectData.Builder(rootType).put("outer", mid).build()

            val result = ObjectRootFieldReference.extractNestedResult(root, listOf("outer", "inner"))
            assertSame(leaf, result)
        }

    @Test
    fun `extractNestedResult with single path element`(): Unit =
        runTest {
            val childType = GraphQLObjectType.newObject().name("Child").build()
            val child = ResolvedEngineObjectData.Builder(childType).put("x", 1).build()

            val parentType = GraphQLObjectType.newObject().name("Parent").build()
            val parent = ResolvedEngineObjectData.Builder(parentType).put("child", child).build()

            val result = ObjectRootFieldReference.extractNestedResult(parent, listOf("child"))
            assertSame(child, result)
        }

    @Test
    fun `extractNestedResult returns null when leaf field is null`(): Unit =
        runTest {
            val rootType = GraphQLObjectType.newObject().name("Root").build()
            val root = ResolvedEngineObjectData.Builder(rootType).put("child", null).build()

            val result = ObjectRootFieldReference.extractNestedResult(root, listOf("child"))
            assertNull(result)
        }

    @Test
    fun `extractNestedResult returns null when leaf is null through namespace`(): Unit =
        runTest {
            val nsType = GraphQLObjectType.newObject().name("Namespace").build()
            val ns = ResolvedEngineObjectData.Builder(nsType).put("create", null).build()

            val rootType = GraphQLObjectType.newObject().name("Root").build()
            val root = ResolvedEngineObjectData.Builder(rootType).put("factory", ns).build()

            val result = ObjectRootFieldReference.extractNestedResult(root, listOf("factory", "create"))
            assertNull(result)
        }

    @Test
    fun `extractNestedResult throws when intermediate segment is not EngineObjectData`(): Unit =
        runTest {
            val rootType = GraphQLObjectType.newObject().name("Root").build()
            val root = ResolvedEngineObjectData.Builder(rootType).put("factory", "not-an-eod").build()

            assertThrows<IllegalStateException> {
                ObjectRootFieldReference.extractNestedResult(root, listOf("factory", "create"))
            }
        }
}
