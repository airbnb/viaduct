package viaduct.graphql.scopes.visitors

import graphql.language.FieldDefinition
import graphql.language.TypeName
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.Test
import viaduct.graphql.scopes.utils.ElementScopeMetadata
import viaduct.graphql.scopes.utils.ScopeDirectiveParser
import viaduct.graphql.scopes.utils.StubRoot
import viaduct.graphql.scopes.utils.buildSchemaTraverser
import viaduct.graphql.scopes.utils.getChildrenForElement

class FilterChildrenVisitorTest {
    /**
     * Simple test that ensures we produce an empty type when a type has no fields in the given scopes
     */
    @Test
    fun `visiting a GraphQLObject type with no fields in scope produce an empty type`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              one: AType
              two: String
            }

            type AType {
              one: String
              two: String
            }
        """
            )

        val elementChildren =
            filterSchema(schema) {
                every {
                    it.metadataForElement(
                        schemaElementNamed("RootQuery")
                    )
                } returns
                    buildElementScopeMetadata(
                        "RootQuery",
                        mapOf(
                            "test-scope" to listOf("one", "two")
                        )
                    )

                every {
                    it.metadataForElement(
                        schemaElementNamed("AType")
                    )
                } returns buildElementScopeMetadata("AType", mapOf())
            }

        getChildrenForElement(elementChildren, "RootQuery")?.map { it.name } shouldContainExactly listOf("one", "two")
        getChildrenForElement(elementChildren, "AType").shouldBeEmpty()
    }

    /**
     * Ensures type extensions don't screw up transformation
     */
    @Test
    fun `filters fields from type extensions`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              one: AType
              two: String
            }

            type AType {
              one: String
              two: String
            }

            extend type AType {
              three: String
            }
        """
            )

        val elementChildren =
            filterSchema(schema) {
                every {
                    it.metadataForElement(
                        schemaElementNamed("RootQuery")
                    )
                } returns
                    buildElementScopeMetadata(
                        "RootQuery",
                        mapOf(
                            "test-scope" to listOf("one", "two")
                        )
                    )

                every {
                    it.metadataForElement(
                        schemaElementNamed("AType")
                    )
                } returns buildElementScopeMetadata("RootQuery", mapOf())
            }

        getChildrenForElement(elementChildren, "RootQuery")?.map { it.name } shouldContainExactly listOf("one", "two")
        getChildrenForElement(elementChildren, "AType").shouldBeEmpty()
    }

    @Test
    fun `properly filters interfaces`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              a: AType
              b: BType
              c: CType
              d: DType
            }

            interface OneInterface {
              one: String
            }

            interface TwoInterface {
              two: String
            }

            interface ThreeInterface {
              three: String
            }

            # Valid, shouldn't be filtered
            type AType implements OneInterface {
              one: String
            }

            # Valid, shouldn't be filtered
            type BType implements OneInterface & TwoInterface {
              one: String
              two: String
            }

            # Valid
            type CType implements OneInterface & TwoInterface & ThreeInterface {
              one: String
              two: String
              three: String
            }

            # Valid
            type DType implements ThreeInterface {
              three: String
              four: String
            }

            # Will become invalid if the "one" field is filtered out
            type EType implements OneInterface {
              one: String
              three: String
            }
       """
            )

        val elementChildren =
            filterSchema(schema) {
                every { it.metadataForElement(schemaElementNamed("RootQuery")) } returns
                    buildElementScopeMetadata("RootQuery", mapOf("test-scope" to listOf("a", "b", "c", "d")))

                every { it.metadataForElement(schemaElementNamed("OneInterface")) } returns
                    buildElementScopeMetadata("OneInterface", mapOf("test-scope" to listOf("one")))

                every { it.metadataForElement(schemaElementNamed("TwoInterface")) } returns
                    buildElementScopeMetadata("TwoInterface", mapOf("test-scope" to listOf("two")))

                every { it.metadataForElement(schemaElementNamed("ThreeInterface")) } returns
                    buildElementScopeMetadata("ThreeInterface", mapOf())

                every { it.metadataForElement(schemaElementNamed("AType")) } returns
                    buildElementScopeMetadata("AType", mapOf("test-scope" to listOf("one")))

                every { it.metadataForElement(schemaElementNamed("BType")) } returns
                    buildElementScopeMetadata("BType", mapOf("test-scope" to listOf("one", "two")))

                every { it.metadataForElement(schemaElementNamed("CType")) } returns
                    buildElementScopeMetadata("CType", mapOf("test-scope" to listOf("one", "two", "three")))

                every { it.metadataForElement(schemaElementNamed("DType")) } returns
                    buildElementScopeMetadata("DType", mapOf("test-scope" to listOf("three", "four")))

                every { it.metadataForElement(schemaElementNamed("EType")) } returns
                    buildElementScopeMetadata("EType", mapOf("test-scope" to listOf("one", "three")))
            }

        getChildrenForElement(elementChildren, "OneInterface")?.map { it.name } shouldContainExactly listOf("one")
        getChildrenForElement(elementChildren, "TwoInterface")?.map { it.name } shouldContainExactly listOf("two")
        getChildrenForElement(elementChildren, "ThreeInterface").shouldBeEmpty()
    }

    @Test
    fun `properly filters enum values`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              one: SomeEnum
              two: AnotherEnum
            }

            # will be removed completely
            enum AnotherEnum {
              ONE
              TWO
              THREE
            }

            # will be filtered to A, B
            enum SomeEnum {
              A
              B
              C
            }
        """
            )

        val elementChildren =
            filterSchema(schema) {
                every { it.metadataForElement(schemaElementNamed("RootQuery")) } returns
                    buildElementScopeMetadata("RootQuery", mapOf("test-scope" to listOf("one", "two")))

                every { it.metadataForElement(schemaElementNamed("SomeEnum")) } returns
                    buildElementScopeMetadata("SomeEnum", mapOf("test-scope" to listOf("A", "B")))

                every { it.metadataForElement(schemaElementNamed("AnotherEnum")) } returns
                    buildElementScopeMetadata("AnotherEnum", mapOf())
            }

        getChildrenForElement(elementChildren, "SomeEnum")?.map { it.name } shouldContainExactly listOf("A", "B")
        getChildrenForElement(elementChildren, "AnotherEnum").shouldBeEmpty()
    }

    /**
     * Should properly filter union members from union types, and also remove empty unions
     */
    @Test
    fun `properly filters union members`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              one: SomeUnion
              two: AnotherUnion
            }

            # will be removed completely
            union AnotherUnion = Bim

            # will be filtered to Foo | Bar
            union SomeUnion = Foo | Bar | Baz

            type Foo {
              one: String
            }

            type Bar {
              one: String
            }

            type Baz {
              one: String
            }

            type Bim {
              one: String
            }
        """
            )

        val doFilterSchema: () -> Map<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?> =
            {
                filterSchema(
                    schema
                ) {
                    every { it.metadataForElement(schemaElementNamed("RootQuery")) } returns
                        buildElementScopeMetadata("RootQuery", mapOf("test-scope" to listOf("one", "two")))

                    every { it.metadataForElement(schemaElementNamed("SomeUnion")) } returns
                        buildElementScopeMetadata("SomeUnion", mapOf("test-scope" to listOf("Type__Foo", "Type__Bar")))

                    every { it.metadataForElement(schemaElementNamed("AnotherUnion")) } returns
                        buildElementScopeMetadata("AnotherUnion", mapOf())

                    every { it.metadataForElement(schemaElementNamed("Foo")) } returns
                        buildElementScopeMetadata("Foo", mapOf("test-scope" to listOf("one")))

                    every { it.metadataForElement(schemaElementNamed("Bar")) } returns
                        buildElementScopeMetadata("Bar", mapOf("test-scope" to listOf("one")))

                    every { it.metadataForElement(schemaElementNamed("Baz")) } returns
                        buildElementScopeMetadata("Baz", mapOf("test-scope" to listOf("one")))

                    every { it.metadataForElement(schemaElementNamed("Bim")) } returns
                        buildElementScopeMetadata("Bim", mapOf())
                }
            }

        val elementChildren = doFilterSchema()

        // Contains the types we expect
        elementChildren.keys.mapNotNull { (it as? GraphQLNamedSchemaElement)?.name } shouldContainAll
            listOf("RootQuery", "SomeUnion", "Foo", "Bar")

        // AnotherUnion should be gone
        getChildrenForElement(elementChildren, "AnotherUnion") shouldBe emptyList()

        // Make sure we filtered out `Baz` from SomeUnion
        getChildrenForElement(elementChildren, "SomeUnion")?.map { it.name } shouldContainExactly listOf("Foo", "Bar")
    }

    /**
     * Tests edge case where you have completely unused types in the schema after removing fields that reference them.
     */
    @Test
    fun `unused types after transformation should be filtered from the schema`() {
        val schema =
            toSchema(
                """
            schema {
              query: RootQuery
            }

            type RootQuery {
              one: AType
              two: CType
            }

            type AType {
              one: String
              two: String
            }

            type BType {
              one: String
              two: CType
              three: DType
            }

            type CType {
              one: String
            }

            type DType {
              one: String
            }
        """
            )

        /**
         * Define some fake scope metadata.
         *
         * RootQuery has test-scope
         * AType has test-scope for one of its fields
         * BType is not included, and thus should be deleted in the final schema
         * CType has test-scope for one of its fields
         */
        val elementChildren =
            filterSchema(schema) {
                every { it.metadataForElement(schemaElementNamed("RootQuery")) } returns
                    buildElementScopeMetadata("RootQuery", mapOf("test-scope" to listOf("one", "two")))

                every { it.metadataForElement(schemaElementNamed("AType")) } returns
                    buildElementScopeMetadata("AType", mapOf("test-scope" to listOf("one")))

                every { it.metadataForElement(schemaElementNamed("BType")) } returns
                    buildElementScopeMetadata("BType", mapOf())

                every { it.metadataForElement(schemaElementNamed("CType")) } returns
                    buildElementScopeMetadata("CType", mapOf("test-scope" to listOf("one")))

                every { it.metadataForElement(schemaElementNamed("DType")) } returns
                    buildElementScopeMetadata("DType", mapOf())
            }

        getChildrenForElement(elementChildren, "RootQuery")?.map { it.name } shouldContainExactly listOf("one", "two")

        // Filtered down to just a single field
        getChildrenForElement(elementChildren, "AType")?.map { it.name } shouldContainExactly listOf("one")

        // No fields are in scope, so it's emptied
        getChildrenForElement(elementChildren, "BType") shouldBe emptyList()

        // Referenced by RootQuery and BType, so sticks around
        getChildrenForElement(elementChildren, "CType")?.map { it.name } shouldContainExactly listOf("one")

        // Only referenced by BType, so it's removed
        getChildrenForElement(elementChildren, "DType") shouldBe emptyList()
    }

    private fun filterSchema(
        schema: GraphQLSchema,
        mockBlock: (mockScopeDirectiveParser: ScopeDirectiveParser) -> Unit
    ): Map<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?> {
        val mockScopeDirectiveParser = spyk(ScopeDirectiveParser(setOf("test-scope")))
        mockBlock(mockScopeDirectiveParser)
        val elementChildren =
            schema.allTypesAsList
                .map {
                    Pair(it as GraphQLSchemaElement, getChildrenForElement(it))
                }.toMap()
                .toMutableMap()
        val visitor =
            FilterChildrenVisitor(
                appliedScopes = setOf("test-scope"),
                scopeDirectiveParser = mockScopeDirectiveParser,
                elementChildren = elementChildren
            )
        buildSchemaTraverser(schema).traverse(StubRoot(schema), visitor)
        return elementChildren
    }

    private fun getChildrenForElement(
        elementChildren: Map<GraphQLSchemaElement, List<GraphQLNamedSchemaElement>?>,
        typeName: String
    ) = elementChildren.entries
        .filter { (it.key as? GraphQLNamedSchemaElement)?.name == typeName }
        .firstOrNull()
        ?.value

    private fun MockKMatcherScope.schemaElementNamed(name: String) =
        match<GraphQLSchemaElement> {
            (it as? GraphQLNamedSchemaElement)?.name == name
        }

    private fun buildElementScopeMetadata(
        name: String,
        elementNamesForScopes: Map<String, List<String>>
    ) = ElementScopeMetadata(
        name,
        elementNamesForScopes.mapValues {
            it.value.map { name ->
                if (name.startsWith("Type__")) {
                    TypeName(name.replace("Type__", ""))
                } else {
                    FieldDefinition(name, null)
                }
            }
        }
    )
}
