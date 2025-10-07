package viaduct.graphql.utils

import graphql.Scalars
import graphql.language.Document
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.parser.Parser
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeVisitor
import graphql.util.TraversalControl.CONTINUE
import graphql.util.TraverserContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GraphQLOssExtensionsTest {
    private val String.asDocument: Document get() = Parser.parse(this)

    @Test
    fun `node allChildren`() {
        val children = "{x}".asDocument.allChildren
        assertEquals(3, children.size)
        assertInstanceOf(OperationDefinition::class.java, children[0])
        assertInstanceOf(SelectionSet::class.java, children[1])
        assertInstanceOf(Field::class.java, children[2])
    }

    @Test
    fun `node allChildrenOfType`() {
        val doc = "{ x { y } }".asDocument
        assertEquals(1, doc.allChildrenOfType<OperationDefinition>().size)
        assertEquals(2, doc.allChildrenOfType<Field>().size)
        assertEquals(2, doc.allChildrenOfType<SelectionSet>().size)
    }

    @Test
    fun `asNamedElement returns named element for named types`() {
        val namedType = Scalars.GraphQLString
        val result = namedType.asNamedElement()
        assertNotNull(result)
        assertInstanceOf(GraphQLNamedSchemaElement::class.java, result)
        assertEquals("String", result.name)
    }

    @Test
    fun `asNamedElement returns named element for object types`() {
        val objectType = GraphQLObjectType.newObject()
            .name("TestObject")
            .field { it.name("testField").type(Scalars.GraphQLString) }
            .build()
        val result = objectType.asNamedElement()
        assertNotNull(result)
        assertEquals("TestObject", result.name)
    }

    @Test
    fun `asNamedElement unwraps wrapped types`() {
        val wrappedType = GraphQLNonNull(GraphQLList(Scalars.GraphQLString))
        val result = wrappedType.asNamedElement()
        assertNotNull(result)
        assertEquals("String", result.name)
    }

    @Test
    fun `asNamedElement unwraps multiple levels of wrapping`() {
        val deeplyWrappedType = GraphQLNonNull(GraphQLList(GraphQLNonNull(GraphQLList(Scalars.GraphQLString))))
        val result = deeplyWrappedType.asNamedElement()
        assertNotNull(result)
        assertEquals("String", result.name)
    }

    @Test
    fun `asNamedElement throws RuntimeException for type that cannot be converted to named element`() {
        val problematicType = object : GraphQLType {
            override fun accept(
                context: TraverserContext<GraphQLSchemaElement>,
                visitor: GraphQLTypeVisitor
            ) = CONTINUE

            override fun copy() = this

            override fun toString() = "ProblematicType"
        }

        val exception = assertThrows(RuntimeException::class.java) {
            problematicType.asNamedElement()
        }

        assert(exception.message?.contains("cannot be cast") == true) {
            "Expected ClassCastException message but got: ${exception.message}"
        }
    }

    @Test
    fun `asMaybeNamedElement returns named element for named types`() {
        val namedType = Scalars.GraphQLString
        val result = namedType.asMaybeNamedElement()
        assertNotNull(result)
        assertEquals("String", result?.name)
    }

    @Test
    fun `asMaybeNamedElement returns named element for object types`() {
        val objectType = GraphQLObjectType.newObject()
            .name("TestObject")
            .field { it.name("testField").type(Scalars.GraphQLString) }
            .build()
        val result = objectType.asMaybeNamedElement()
        assertNotNull(result)
        assertEquals("TestObject", result?.name)
    }

    @Test
    fun `asMaybeNamedElement unwraps wrapped types`() {
        val wrappedType = GraphQLNonNull(GraphQLList(Scalars.GraphQLString))
        val result = wrappedType.asMaybeNamedElement()
        assertNotNull(result)
        assertEquals("String", result?.name)
    }

    @Test
    fun `asMaybeNamedElement unwraps multiple levels of wrapping`() {
        val deeplyWrappedType = GraphQLNonNull(GraphQLList(GraphQLNonNull(GraphQLList(Scalars.GraphQLString))))
        val result = deeplyWrappedType.asMaybeNamedElement()
        assertNotNull(result)
        assertEquals("String", result?.name)
    }

    @Test
    fun `allChildren handles nested structures correctly`() {
        val doc = "{ level1 { level2 { level3 } } }".asDocument
        val children = doc.allChildren
        assertEquals(7, children.size)

        val fields = children.filterIsInstance<Field>()
        assertEquals(3, fields.size)
        assertEquals("level1", fields[0].name)
        assertEquals("level2", fields[1].name)
        assertEquals("level3", fields[2].name)
    }

    @Test
    fun `allChildren handles simple selections`() {
        val doc = "{ simpleField }".asDocument
        val children = doc.allChildren
        assertEquals(3, children.size)
        assertInstanceOf(OperationDefinition::class.java, children[0])
        assertInstanceOf(SelectionSet::class.java, children[1])
        assertInstanceOf(Field::class.java, children[2])
    }
}
