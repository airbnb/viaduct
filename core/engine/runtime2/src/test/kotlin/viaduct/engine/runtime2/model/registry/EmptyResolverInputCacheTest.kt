@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.registry

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.errors.UnsetFieldException
import viaduct.graphql.schema.ViaductSchema

class EmptyResolverInputCacheTest {
    @Test
    fun `typed empty inputs retain schema identity and remain absent`() {
        val fixture = TestWorld.fromSDL("type Query { value: Value } type Value { text: String }")
        val registry = fixture.assumptions.resolverRegistry
        val query = fixture.schema.requireQueryTypeDef()
        val value = fixture.schema.requireType("Value") as ViaductSchema.Object
        val empty = registry.emptyResolverInput(query)
        assertSame(empty, registry.emptyResolverInput(query))
        assertSame(query, empty.schemaType)
        assertFalse(empty.getSelections().any())
        assertFailsWith<UnsetFieldException> { empty.get("value") }
        assertNotSame(empty, registry.emptyResolverInput(value))

        val other = TestWorld.fromSDL("type Query { value: Value } type Value { text: String }")
        assertNotSame(empty, other.assumptions.resolverRegistry.emptyResolverInput(other.schema.requireQueryTypeDef()))
    }

    @Test
    fun `a registry decorator does not inherit the prepared registry cache`() {
        val fixture = TestWorld.fromSDL("type Query { value: Int }")
        val original = fixture.assumptions.resolverRegistry
        val decorator = object : ResolverRegistry by original {}
        val type = fixture.schema.requireQueryTypeDef()
        assertNotSame(original.emptyResolverInput(type), decorator.emptyResolverInput(type))
    }
}
