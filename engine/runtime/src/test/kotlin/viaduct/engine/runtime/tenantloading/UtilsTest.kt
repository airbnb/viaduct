package viaduct.engine.runtime.tenantloading

import graphql.Scalars.GraphQLInt
import graphql.Scalars.GraphQLString
import graphql.schema.GraphQLList.list
import graphql.schema.GraphQLNonNull.nonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UtilsTest {
    @Test
    fun `Type -- no wrappers`() {
        val t = Type(GraphQLInt)
        assertTrue(t.effectivelyNullable)
        assertSame(t, t.unwrapIfNonNull)
        assertSame(t, t.unwrapIfList)
        assertFalse(t.isNonNull)
        assertFalse(t.isList)
    }

    @Test
    fun `Type -- nonNull`() {
        val t = Type(nonNull(GraphQLInt))
        assertFalse(t.effectivelyNullable)
        assertSame(t, t.unwrapIfList)
        assertTrue(t.isNonNull)
        assertEquals(GraphQLInt, t.unwrapIfNonNull.type)
        assertFalse(t.isList)
    }

    @Test
    fun `Type -- list`() {
        val t = Type(list(GraphQLInt))
        assertEquals(t, t.unwrapIfNonNull)
        assertEquals(GraphQLInt, t.unwrapIfList.type)
        assertFalse(t.isNonNull)
        assertTrue(t.isList)
    }

    @Test
    fun `areTypesCompatible -- non-nullable type is compatible with any nullability`() {
        assertTrue(
            areTypesCompatible(
                locationType = Type(GraphQLInt),
                variableType = Type(nonNull(GraphQLInt)),
            )
        )
        assertTrue(
            areTypesCompatible(
                locationType = Type(nonNull(GraphQLInt)),
                variableType = Type(nonNull(GraphQLInt)),
            )
        )
    }

    @Test
    fun `areTypesCompatible -- nullable type is not compatible with non-nullable type`() {
        assertFalse(
            areTypesCompatible(
                locationType = Type(nonNull(GraphQLInt)),
                variableType = Type(GraphQLInt),
            )
        )
    }

    @Test
    fun `areTypesCompatible -- effectively-nullable non-nullable type is compatible with nullable type`() {
        assertTrue(
            areTypesCompatible(
                locationType = Type(GraphQLInt),
                variableType = Type(nonNull(GraphQLInt)) + Type.Property.NullableTraversalPath,
            )
        )
    }

    @Test
    fun `areTypesCompatible -- effectively-nullable non-nullable type is not compatible with non-nullable type`() {
        assertFalse(
            areTypesCompatible(
                locationType = Type(nonNull(GraphQLInt)),
                variableType = Type(nonNull(GraphQLInt)) + Type.Property.NullableTraversalPath,
            )
        )
    }

    @Test
    fun `areTypesCompatible -- nullable types are compatible with fields that have a default value`() {
        assertTrue(
            areTypesCompatible(
                locationType = Type(GraphQLInt) + Type.Property.HasDefault,
                variableType = Type(GraphQLInt),
            )
        )
        assertTrue(
            areTypesCompatible(
                locationType = Type(nonNull(GraphQLInt)) + Type.Property.HasDefault,
                variableType = Type(GraphQLInt),
            )
        )
    }

    @Test
    fun `areTypesCompatible -- fields with wrong type are not compatible with field with default value`() {
        assertFalse(
            areTypesCompatible(
                locationType = Type(GraphQLInt) + Type.Property.HasDefault,
                variableType = Type(GraphQLString),
            )
        )
    }

    @Test
    fun `areTypesCompatible -- wrong type scalar is not compatible with list with default value`() {
        // Int → [String]! (with default)
        assertFalse(
            areTypesCompatible(
                locationType = Type(nonNull(list(GraphQLString))) + Type.Property.HasDefault,
                variableType = Type(GraphQLInt)
            )
        )
    }

    @Test
    fun `areTypesCompatible -- scalar type is coercible to list type`() {
        assertTrue(
            areTypesCompatible(
                locationType = Type(list(GraphQLInt)),
                variableType = Type(GraphQLInt)
            )
        )
    }

    @Test
    fun `areTypesCompatible -- scalar type is not coercible to list with different base type`() {
        assertFalse(
            areTypesCompatible(
                locationType = Type(list(GraphQLInt)),
                variableType = Type(GraphQLString)
            )
        )
    }

    @Test
    fun `areTypesCompatible -- non-null scalar to nested list`() {
        // String! -> [[String!]!]!
        assertTrue(
            areTypesCompatible(
                locationType = Type(nonNull(list(nonNull(list(nonNull(GraphQLString)))))),
                variableType = Type(nonNull(GraphQLString))
            )
        )
    }

    @Test
    fun `areTypesCompatible -- list is coercible to list of list`() {
        // [String!]! -> [[String!]!]!
        assertTrue(
            areTypesCompatible(
                locationType = Type(nonNull(list(nonNull(list(nonNull(GraphQLString)))))),
                variableType = Type(nonNull(list(nonNull(GraphQLString))))
            )
        )
    }

    @Test
    fun `areTypesCompatible -- nullable scalar is coercible to non-nullable list with nullable base type`() {
        // String -> [[String]]!
        assertTrue(
            areTypesCompatible(
                locationType = Type(nonNull(list(list(GraphQLString)))),
                variableType = Type(GraphQLString)
            )
        )
    }

    @Test
    fun `areTypesCompatible -- nullable list elements not coercible to non-null list elements`() {
        assertFalse(
            areTypesCompatible(
                locationType = Type(list(nonNull(GraphQLString))),
                variableType = Type(list(GraphQLString))
            )
        )
    }

    @Test
    fun `areTypesCompatible -- list is not coercible to scalar`() {
        assertFalse(
            areTypesCompatible(
                locationType = Type(GraphQLString),
                variableType = Type(list(GraphQLString))
            )
        )
    }

    @Test
    fun `isListish`() {
        assertFalse(GraphQLInt.isListish)
        assertTrue(list(GraphQLInt).isListish)
        assertFalse(nonNull(GraphQLInt).isListish)
        assertTrue(nonNull(list(GraphQLInt)).isListish)
        assertTrue(list(nonNull(GraphQLInt)).isListish)
        assertTrue(list(list(GraphQLInt)).isListish)
    }
}
