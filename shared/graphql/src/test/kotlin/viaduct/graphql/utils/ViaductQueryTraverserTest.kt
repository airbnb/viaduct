package viaduct.graphql.utils

import graphql.analysis.QueryReducer
import graphql.analysis.QueryTraversalOptions
import graphql.analysis.QueryVisitor
import graphql.analysis.QueryVisitorFieldEnvironment
import graphql.analysis.QueryVisitorFieldEnvironmentImpl
import graphql.analysis.QueryVisitorFragmentDefinitionEnvironment
import graphql.analysis.QueryVisitorStub
import graphql.execution.CoercedVariables
import graphql.language.ArrayValue
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.IntValue
import graphql.language.NodeUtil
import graphql.language.OperationDefinition
import graphql.language.StringValue
import graphql.parser.Parser
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.util.TraversalControl
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.Collections.emptyMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ViaductQueryTraverserTest {
    private fun createSchema(schemaString: String): GraphQLSchema {
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(schemaString)
        )
    }

    private fun createQuery(query: String): Document {
        val parser = Parser()
        return parser.parseDocument(query)
    }

    private fun createQueryTraversal(
        document: Document,
        schema: GraphQLSchema,
        variables: Map<String, Any> = emptyMap()
    ): ViaductQueryTraverser {
        return ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .document(document)
            .variables(variables)
            .build()
    }

    private fun mockQueryVisitor(): QueryVisitor {
        val mock = mockk<QueryVisitor>(relaxed = true)

        every {
            mock.visitFieldWithControl(any())
        } answers {
            mock.visitField(firstArg())
            TraversalControl.CONTINUE
        }

        every {
            mock.visitArgument(any())
        } returns TraversalControl.CONTINUE

        every {
            mock.visitArgumentValue(any())
        } returns TraversalControl.CONTINUE

        return mock
    }

    private lateinit var visitor: QueryVisitor

    @BeforeEach
    fun setUp() {
        visitor = mockQueryVisitor()
    }

    @Test
    fun `test preOrder for visitField`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )

        val query = createQuery(
            """
            {foo { subFoo} bar }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        queryTraversal.visitPreOrder(visitor)

        verifyOrder {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query" &&
                        it.selectionSetContainer == null
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo" &&
                        it.selectionSetContainer == it.parentEnvironment.field
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )
        }
    }

    @Test
    fun `test postOrder for visitField`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )

        val query = createQuery(
            """
            {foo { subFoo} bar }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        queryTraversal.visitPostOrder(visitor)

        verifyOrder {
            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo" &&
                        it.selectionSetContainer == it.parentEnvironment.field
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query" &&
                        it.selectionSetContainer == null
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )
        }
    }

    @Test
    fun `test for visitArgs and visitArgsValue`() {
        val schema = createSchema(
            """
                type Query{
                    foo (complexArg : Complex, simpleArg : String) : Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }

                input Complex {
                    name : String
                    moreComplex : [MoreComplex]
                }

                input MoreComplex {
                    height : Int
                    weight : Int
                }
            """
        )
        val query = createQuery(
            """
            {
                foo( complexArg : { name : "Ted", moreComplex : [{height : 100, weight : 200}] } , simpleArg : "Hi" ) {
                    subFoo
                }
                bar
            }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        queryTraversal.visitPreOrder(visitor)

        verify {
            visitor.visitArgument(
                match {
                    it.argument.name == "complexArg" &&
                        (it.graphQLArgument.type as GraphQLInputObjectType).name == "Complex"
                }
            )

            visitor.visitArgument(
                match {
                    it.argument.name == "simpleArg" &&
                        (it.graphQLArgument.type as GraphQLScalarType).name == "String"
                }
            )

            visitor.visitArgumentValue(
                match {
                    it.graphQLArgument.name == "complexArg" &&
                        it.argumentInputValue.name == "name" &&
                        it.argumentInputValue.value is StringValue
                }
            )

            visitor.visitArgumentValue(
                match {
                    it.graphQLArgument.name == "complexArg" &&
                        it.argumentInputValue.name == "moreComplex" &&
                        it.argumentInputValue.value is ArrayValue
                }
            )

            visitor.visitArgumentValue(
                match {
                    it.graphQLArgument.name == "complexArg" &&
                        // TODO: Figure out why this isn't getting set
                        // it.argumentInputValue.parent.name == "moreComplex" &&
                        it.argumentInputValue.name == "weight" &&
                        it.argumentInputValue.value is IntValue
                }
            )

            visitor.visitArgumentValue(
                match {
                    it.graphQLArgument.name == "complexArg" &&
                        // TODO: Figure out why this isn't getting set
                        // it.argumentInputValue.parent.name == "moreComplex" &&
                        it.argumentInputValue.name == "height" &&
                        it.argumentInputValue.value is IntValue &&
                        it.argumentInputValue.inputValueDefinition is GraphQLInputObjectField
                }
            )

            visitor.visitArgumentValue(
                match {
                    it.graphQLArgument.name == "simpleArg" &&
                        it.argumentInputValue.name == "simpleArg" &&
                        it.argumentInputValue.value is StringValue &&
                        it.argumentInputValue.inputValueDefinition is GraphQLArgument
                }
            )
        }

        clearMocks(mockQueryVisitor())

        queryTraversal.visitPostOrder(visitor)

        verify {
            visitor.visitArgument(
                match {
                    it.argument.name == "complexArg" &&
                        (it.graphQLArgument.type as GraphQLInputObjectType).name == "Complex"
                }
            )

            visitor.visitArgument(
                match {
                    it.argument.name == "simpleArg" &&
                        (it.graphQLArgument.type as GraphQLScalarType).name == "String"
                }
            )
        }
    }

    @Test
    fun `test preOrder order for inline fragments`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
                {
                    ...F1
                }
                fragment F1 on Query {
                    ...F2
                    ...F3
                }
                fragment F2 on Query {
                    bar
                }
                fragment F3 on Query {
                    bar
                }
                """
        )

        val fragmentF1 = query.definitions[1]
        assert(fragmentF1 is FragmentDefinition)
        val fragmentF2 = query.definitions[2]
        assert(fragmentF2 is FragmentDefinition)
        val fragmentF3 = query.definitions[3]
        assert(fragmentF3 is FragmentDefinition)

        val fragmentSpreadRoot = query.definitions[0].children[0].children[0]
        assert(fragmentSpreadRoot is FragmentSpread)
        val fragmentSpreadLeft = (fragmentF1 as FragmentDefinition).selectionSet.children[0]
        assert(fragmentSpreadLeft is FragmentSpread)
        val fragmentSpreadRight = fragmentF1.selectionSet.children[1]

        val queryTraversal = createQueryTraversal(query, schema)
        queryTraversal.visitPostOrder(visitor)

        verify {
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadRoot &&
                        it.fragmentDefinition == fragmentF1
                }
            )
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadLeft &&
                        it.fragmentDefinition == fragmentF2
                }
            )
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadRight &&
                        it.fragmentDefinition == fragmentF3
                }
            )
        }
    }

    @Test
    fun `test postOrder order for inline fragments`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
                {
                    ...F1
                }
                fragment F1 on Query {
                    ...F2
                    ...F3
                }
                fragment F2 on Query {
                    bar
                }
                fragment F3 on Query {
                    bar
                }
                """
        )

        val fragmentF1 = query.definitions[1]
        assert(fragmentF1 is FragmentDefinition)
        val fragmentF2 = query.definitions[2]
        assert(fragmentF2 is FragmentDefinition)
        val fragmentF3 = query.definitions[3]
        assert(fragmentF3 is FragmentDefinition)

        val fragmentSpreadRoot = query.definitions[0].children[0].children[0]
        assert(fragmentSpreadRoot is FragmentSpread)
        val fragmentSpreadLeft = (fragmentF1 as FragmentDefinition).selectionSet.children[0]
        assert(fragmentSpreadLeft is FragmentSpread)
        val fragmentSpreadRight = fragmentF1.selectionSet.children[1]

        val queryTraversal = createQueryTraversal(query, schema)
        queryTraversal.visitPostOrder(visitor)

        verify {
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadLeft &&
                        it.fragmentDefinition == fragmentF2
                }
            )
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadRight &&
                        it.fragmentDefinition == fragmentF3
                }
            )
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadRoot &&
                        it.fragmentDefinition == fragmentF1
                }
            )
        }
    }

    @Test
    fun `test preOrder order for fragment spreads`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
                {
                    ...F1
                }
                fragment F1 on Query {
                    ...F2
                    ...F3
                }
                fragment F2 on Query {
                    bar
                }
                fragment F3 on Query {
                    bar
                }
                """
        )

        val fragmentF1 = query.definitions[1]
        assert(fragmentF1 is FragmentDefinition)
        val fragmentF2 = query.definitions[2]
        assert(fragmentF2 is FragmentDefinition)
        val fragmentF3 = query.definitions[3]
        assert(fragmentF3 is FragmentDefinition)

        val fragmentSpreadRoot = query.definitions[0].children[0].children[0]
        assert(fragmentSpreadRoot is FragmentSpread)
        val fragmentSpreadLeft = (fragmentF1 as FragmentDefinition).selectionSet.children[0]
        assert(fragmentSpreadLeft is FragmentSpread)
        val fragmentSpreadRight = fragmentF1.selectionSet.children[1]
        assert(fragmentSpreadRight is FragmentSpread)

        val queryTraversal = createQueryTraversal(query, schema)
        queryTraversal.visitPreOrder(visitor)

        verifyOrder {
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadRoot &&
                        it.fragmentDefinition == fragmentF1
                }
            )
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadLeft &&
                        it.fragmentDefinition == fragmentF2
                }
            )
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadRight &&
                        it.fragmentDefinition == fragmentF3
                }
            )
        }
    }

    @Test
    fun `test postOrder order for fragment spreads`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
                {
                    ...F1
                }
                fragment F1 on Query {
                    ...F2
                    ...F3
                }
                fragment F2 on Query {
                    bar
                }
                fragment F3 on Query {
                    bar
                }
                """
        )

        val fragmentF1 = query.definitions[1]
        assert(fragmentF1 is FragmentDefinition)
        val fragmentF2 = query.definitions[2]
        assert(fragmentF2 is FragmentDefinition)
        val fragmentF3 = query.definitions[3]
        assert(fragmentF3 is FragmentDefinition)

        val fragmentSpreadRoot = query.definitions[0].children[0].children[0]
        assert(fragmentSpreadRoot is FragmentSpread)
        val fragmentSpreadLeft = (fragmentF1 as FragmentDefinition).selectionSet.children[0]
        assert(fragmentSpreadLeft is FragmentSpread)
        val fragmentSpreadRight = fragmentF1.selectionSet.children[1]
        assert(fragmentSpreadRight is FragmentSpread)

        val queryTraversal = createQueryTraversal(query, schema)
        queryTraversal.visitPostOrder(visitor)

        verifyOrder {
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadLeft &&
                        it.fragmentDefinition == fragmentF2
                }
            )
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadRight &&
                        it.fragmentDefinition == fragmentF3
                }
            )
            visitor.visitFragmentSpread(
                match {
                    it.fragmentSpread == fragmentSpreadRoot &&
                        it.fragmentDefinition == fragmentF1
                }
            )
        }
    }

    @Test
    fun `test preOrder and postOrder order for fragment definitions and raw variables`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
                {
                    ...F1
                }

                fragment F1 on Query {
                    foo {
                        subFoo
                    }
                }
                """
        )

        val fragments = NodeUtil.getFragmentsByName(query)
        val fragmentF1 = fragments["F1"]
        requireNotNull(fragmentF1) { "Fragment F1 not found" }

        val queryTraversal = ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .root(fragmentF1)
            .rootParentType(schema.queryType)
            .fragmentsByName(fragments)
            .variables(emptyMap())
            .build()

        queryTraversal.visitPreOrder(visitor)

        verify {
            visitor.visitFragmentDefinition(
                match { it: QueryVisitorFragmentDefinitionEnvironment ->
                    it.fragmentDefinition == fragments["F1"]
                }
            )
        }

        queryTraversal.visitPostOrder(visitor)

        verify {
            visitor.visitFragmentDefinition(
                match { it: QueryVisitorFragmentDefinitionEnvironment ->
                    it.fragmentDefinition == fragments["F1"]
                }
            )
        }
    }

    @Test
    fun `test preOrder and postOrder order for fragment definitions and coerced variables`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
                {
                    ...F1
                }

                fragment F1 on Query {
                    foo {
                        subFoo
                    }
                }
                """
        )

        val fragments = NodeUtil.getFragmentsByName(query)
        val fragmentF1 = fragments["F1"]
        requireNotNull(fragmentF1) { "Fragment F1 not found" }

        val queryTraversal = ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .root(fragmentF1)
            .rootParentType(schema.queryType)
            .fragmentsByName(fragments)
            .coercedVariables(CoercedVariables.emptyVariables())
            .build()

        queryTraversal.visitPreOrder(visitor)

        verify {
            visitor.visitFragmentDefinition(
                match { it: QueryVisitorFragmentDefinitionEnvironment ->
                    it.fragmentDefinition == fragments["F1"]
                }
            )
        }

        queryTraversal.visitPostOrder(visitor)

        verify {
            visitor.visitFragmentDefinition(
                match { it: QueryVisitorFragmentDefinitionEnvironment ->
                    it.fragmentDefinition == fragments["F1"]
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `works for mutations`(visitFn: String) {
        val schema = createSchema(
            """
                type Query {
                  a: String
                }
                type Mutation{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
                schema {mutation: Mutation, query: Query}
            """
        )
        val query = createQuery(
            """
            mutation M{bar foo { subFoo} }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Mutation"
                }
            )
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Mutation"
                }
            )
            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `works for subscriptions`(visitFn: String) {
        val schema = createSchema(
            """
                type Query {
                  a: String
                }
                type Subscription{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
                schema {subscription: Subscription, query: Query}
            """
        )
        val query = createQuery(
            """
            subscription S{bar foo { subFoo} }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Subscription"
                }
            )
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Subscription"
                }
            )
            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `field with arguments`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo(arg1: String, arg2: Boolean): String
                }
            """
        )
        val query = createQuery(
            """
            query myQuery(${'$'}myVar: String){foo(arg1: ${'$'}myVar, arg2: true)}
            """
        )
        val variables = mapOf("myVar" to "hello")
        val queryTraversal = createQueryTraversal(query, schema, variables)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        it.arguments == mapOf("arg1" to "hello", "arg2" to true)
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `traverse a query when a default variable is a list`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo(arg1: [String]): String
                }
            """
        )
        val query = createQuery(
            """
            query myQuery(${'$'}myVar: [String] = ["hello default"]) {foo(arg1: ${'$'}myVar)}
            """
        )
        val variables = mapOf("myVar" to listOf("hello"))
        val queryTraversal = createQueryTraversal(query, schema, variables)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        it.arguments == mapOf("arg1" to listOf("hello"))
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `traverse a query when a default variable is a list and query does not specify variables`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo(arg1: [String]): String
                }
            """
        )
        val query = createQuery(
            """
            query myQuery(${'$'}myVar: [String] = ["hello default"]) {foo(arg1: ${'$'}myVar)}
            """
        )
        val variables = emptyMap<String, Any>()
        val queryTraversal = createQueryTraversal(query, schema, variables)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        it.arguments == mapOf("arg1" to listOf("hello default"))
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `simple query`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {bar foo { subFoo} }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `query with non null and lists`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo!
                    foo2: [Foo]
                    foo3: [Foo!]
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {bar foo { subFoo} foo2 { subFoo} foo3 { subFoo}}
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        ((it.fieldDefinition.type as GraphQLNonNull).wrappedType as GraphQLNamedType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        it.fieldsContainer.name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        ((it.parentEnvironment.fieldDefinition.type as GraphQLNonNull).wrappedType as GraphQLNamedType).name == "Foo"
                }
            )

            // Check that there are 2 more subFoo fields being visited (for foo2 and foo3)
            visitor.visitField(
                match {
                    it.field.name == "subFoo"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `query with inline fragment`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {
                bar
                ... on Query {
                    foo
                    { subFoo
                    }
                }
            }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        val inlineFragment = query.children[0].children[0].children[1]
        assert(inlineFragment is InlineFragment)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query" &&
                        it.selectionSetContainer == inlineFragment
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `query with inline fragment without condition`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {
                bar
                ... {
                    foo
                    { subFoo
                    }
                }
            }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `query with fragment`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {
                bar
                ...Test
            }
            fragment Test on Query {
                foo
                { subFoo
                }
            }

            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        // We need to get the fragment definition to check it in assertions
        val fragmentDefinition = query.children[1]
        assert(fragmentDefinition is FragmentDefinition)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query" &&
                        it.selectionSetContainer == fragmentDefinition
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `query with skipped fields`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {
                bar
                ...Test @skip(if: true)
            }
            fragment Test on Query {
                foo
                { subFoo
                }
            }

            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        // Unlike the original QueryTraverser, ViaductQueryTraverser will still visit these fields
        // even if they have @skip directive
        verify {
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `query with skipped fields and variables`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            query MyQuery(${'$'}variableFoo: Boolean) {
                bar
                ...Test @skip(if: ${'$'}variableFoo)
            }
            fragment Test on Query {
                foo
                { subFoo
                }
            }

            """
        )
        val variables = mapOf("variableFoo" to true)
        val queryTraversal = createQueryTraversal(query, schema, variables)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        // Unlike the original QueryTraverser, ViaductQueryTraverser will still visit these fields
        // even if they have @skip directive
        verify {
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo" &&
                        it.parentEnvironment.field.name == "foo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLNamedType).name == "Foo"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `nested fragments`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo1
                    bar: String
                }
                type Foo1 {
                    string: String
                    subFoo: Foo2
                }
                type Foo2 {
                    otherString: String
                }
            """
        )
        val query = createQuery(
            """
            query MyQuery(${'$'}variableFoo: Boolean) {
                bar
                ...Test @include(if: ${'$'}variableFoo)
            }
            fragment Test on Query {
                bar
                foo {
                    ...OnFoo1
                }
            }

            fragment OnFoo1 on Foo1 {
                string
                subFoo {
                    ... on Foo2 {
                       otherString
                    }
                }
            }

            """
        )
        val variables = mapOf("variableFoo" to true)
        val queryTraversal = createQueryTraversal(query, schema, variables)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            // We verify both 'bar' fields are visited (one from the root, one from the fragment)
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo1" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "string" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo1"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo2" &&
                        (it.parentType as GraphQLNamedType).name == "Foo1"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "otherString" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Foo2" &&
                        it.parentEnvironment.field.name == "subFoo" &&
                        (it.parentEnvironment.fieldDefinition.type as GraphQLObjectType).name == "Foo2" &&
                        (it.parentEnvironment.parentType as GraphQLNamedType).name == "Foo1"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `skipped Fragment`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo1
                    bar: String
                }
                type Foo1 {
                    string: String
                    subFoo: Foo2
                }
                type Foo2 {
                    otherString: String
                }
            """
        )
        val query = createQuery(
            """
            query MyQuery(${'$'}variableFoo: Boolean) {
                bar
                ...Test @include(if: ${'$'}variableFoo)
            }
            fragment Test on Query {
                bar
            }
            """
        )
        val variables = mapOf("variableFoo" to false)
        val queryTraversal = createQueryTraversal(query, schema, variables)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        // Unlike the original QueryTraverser, ViaductQueryTraverser will still visit the fragment
        verify {
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            // ViaductQueryTraverser still visits this field even though the fragment is excluded by @include(if: false)
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `skipped inline Fragment`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo1
                    bar: String
                }
                type Foo1 {
                    string: String
                    subFoo: Foo2
                }
                type Foo2 {
                    otherString: String
                }
            """
        )
        val query = createQuery(
            """
            query MyQuery(${'$'}variableFoo: Boolean) {
                bar
                ...@include(if: ${'$'}variableFoo) {
                    foo
                }
            }
            """
        )
        val variables = mapOf("variableFoo" to false)
        val queryTraversal = createQueryTraversal(query, schema, variables)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        // Unlike the original QueryTraverser, ViaductQueryTraverser will still visit all fields
        verify {
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            // ViaductQueryTraverser still visits this field even though the inline fragment is excluded by @include(if: false)
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo1" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `skipped Field`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo1
                    bar: String
                }
                type Foo1 {
                    string: String
                    subFoo: Foo2
                }
                type Foo2 {
                    otherString: String
                }
            """
        )

        val query = createQuery(
            """
            query MyQuery(${'$'}variableFoo: Boolean) {
                bar
                foo @include(if: ${'$'}variableFoo)
            }
            """
        )
        val variables = mapOf("variableFoo" to false)
        val queryTraversal = createQueryTraversal(query, schema, variables)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        // Unlike the original QueryTraverser, ViaductQueryTraverser will still visit all fields
        verify {
            visitor.visitField(
                match {
                    it.field.name == "bar" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            // ViaductQueryTraverser still visits this field even though it is excluded by @include(if: false)
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLObjectType).name == "Foo1" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )
        }
    }

    @Test
    fun `reduce preOrder`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {foo { subFoo} bar }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        val reducer = mockk<QueryReducer<Int?>>(relaxed = true)

        every {
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "foo" }, 1)
        } returns 2

        every {
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "subFoo" }, 2)
        } returns 3

        every {
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "bar" }, 3)
        } returns 4

        val result = queryTraversal.reducePreOrder(reducer, 1)

        assert(result == 4)

        verifyOrder {
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "foo" }, 1)
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "subFoo" }, 2)
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "bar" }, 3)
        }
    }

    @Test
    fun `reduce postOrder`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {foo { subFoo} bar }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        val reducer = mockk<QueryReducer<Int?>>(relaxed = true)

        every {
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "subFoo" }, 1)
        } returns 2

        every {
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "foo" }, 2)
        } returns 3

        every {
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "bar" }, 3)
        } returns 4

        val result = queryTraversal.reducePostOrder(reducer, 1)

        assert(result == 4)

        verifyOrder {
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "subFoo" }, 1)
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "foo" }, 2)
            reducer.reduceField(match<QueryVisitorFieldEnvironment> { it.field.name == "bar" }, 3)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `works for interfaces`(visitFn: String) {
        val schema = createSchema(
            """
                type Query {
                  a: Node
                }

                interface Node {
                  id: ID!
                }

                type Person implements Node {
                  id: ID!
                  name: String
                }

                schema {query: Query}
            """
        )
        val query = createQuery(
            """
            {a {id... on Person {name}}}
        """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "a" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "Node" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "name" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Person"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "id" &&
                        ((it.fieldDefinition.type as GraphQLNonNull).wrappedType as GraphQLNamedType).name == "ID" &&
                        (it.parentType as GraphQLNamedType).name == "Node"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `works for unions`(visitFn: String) {
        val schema = createSchema(
            """
                type Query {
                  foo: CatOrDog
                }

                type Cat {
                    catName: String
                }

                type Dog {
                    dogName: String
                }
                union CatOrDog = Cat | Dog

                schema {query: Query}
            """
        )
        val query = createQuery(
            """
            {foo {... on Cat {catName} ... on Dog {dogName}} }
        """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "CatOrDog" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "catName" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Cat" &&
                        it.fieldsContainer.name == "Cat"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "dogName" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Dog" &&
                        it.fieldsContainer.name == "Dog"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `works for modified types (non null list elements)`(visitFn: String) {
        val schema = createSchema(
            """
                type Query {
                  foo: [CatOrDog!]
                  bar: [Bar!]!
                }

                type Cat {
                    catName: String
                }

                type Bar {
                    id: String
                }

                type Dog {
                    dogName: String
                }

                union CatOrDog = Cat | Dog

                schema {query: Query}
            """
        )
        val query = createQuery(
            """
            {foo {... on Cat {catName} ... on Dog {dogName}} bar {id}}
        """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        it.fieldDefinition.type.toString() == "[CatOrDog!]" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "catName" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Cat" &&
                        it.fieldsContainer.name == "Cat"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "dogName" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "Dog" &&
                        it.fieldsContainer.name == "Dog"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "id" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        it.parentType.toString().contains("[Bar!]!") && // Non-null List of non-null Bar
                        it.fieldsContainer.name == "Bar"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `works with introspection fields`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {foo {__typename subFoo}
            __schema{  types { name } }
            __type(name: "Foo") { name }
            }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "foo" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "Foo" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "__schema" &&
                        ((it.fieldDefinition.type as GraphQLNonNull).wrappedType as GraphQLNamedType).name == "__Schema" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "__type" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "__Type" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "types"
                }
            )

            // Two name fields: one from __schema.types.name and one from __type.name
            visitor.visitField(
                match {
                    it.field.name == "name"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "name"
                }
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visitPreOrder", "visitPostOrder"])
    fun `#763 handles union types and introspection fields`(visitFn: String) {
        val schema = createSchema(
            """
                type Query{
                    someObject: SomeObject
                }
                type SomeObject {
                    someUnionType: SomeUnionType
                }

                union SomeUnionType = TypeX | TypeY

                type TypeX {
                    field1 : String
                }

                type TypeY {
                    field2 : String
                }
            """
        )
        val query = createQuery(
            """
            {
            someObject {
                someUnionType {
                    __typename
                    ... on TypeX {
                        field1
                    }
                    ... on TypeY {
                        field2
                    }
                }
            }
        }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        when (visitFn) {
            "visitPreOrder" -> queryTraversal.visitPreOrder(visitor)
            "visitPostOrder" -> queryTraversal.visitPostOrder(visitor)
        }

        verify {
            visitor.visitField(
                match {
                    it.field.name == "someObject" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "SomeObject" &&
                        (it.parentType as GraphQLNamedType).name == "Query"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "someUnionType" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "SomeUnionType" &&
                        (it.parentType as GraphQLNamedType).name == "SomeObject"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "__typename" &&
                        ((it.fieldDefinition.type as GraphQLNonNull).wrappedType as GraphQLNamedType).name == "String"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "field1" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "TypeX"
                }
            )

            visitor.visitField(
                match {
                    it.field.name == "field2" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "TypeY"
                }
            )
        }
    }

    @Test
    fun `can select an arbitrary root node with coerced variables as plain map`() {
        // When using an arbitrary root node, there is no variable definition context available.
        // Thus the variables must have already been coerced, but may appear as a plain map rather than CoercedVariables
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                }
                type Foo {
                    subFoo: SubFoo
                }
                type SubFoo {
                   id: String
                }
            """
        )
        val query = createQuery(
            """
            {foo { subFoo {id}} }
            """
        )

        // Extract subFoo field to use as root
        val subFooField = query.children[0].children[0].children[0].children[0].children[0]
        assert(subFooField.toString().contains("subFoo")) { "Expected field to contain 'subFoo' but was '$subFooField'" }

        val rootParentType = schema.getType("Foo") as GraphQLCompositeType
        val queryTraversal = ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .root(subFooField)
            .rootParentType(rootParentType)
            .variables(emptyMap())
            .fragmentsByName(emptyMap())
            .build()

        queryTraversal.visitPreOrder(visitor)

        verifyOrder {
            // First the subFoo field itself is visited
            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "SubFoo"
                }
            )

            // Then its child (id) field is visited
            visitor.visitField(
                match {
                    it.field.name == "id" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "SubFoo"
                }
            )
        }
    }

    @Test
    fun `can select an arbitrary root node with coerced variables`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                }
                type Foo {
                    subFoo: SubFoo
                }
                type SubFoo {
                   id: String
                }
            """
        )
        val query = createQuery(
            """
            {foo { subFoo {id}} }
            """
        )

        // Extract subFoo field to use as root
        val subFooField = query.children[0].children[0].children[0].children[0].children[0]
        assert(subFooField.toString().contains("subFoo")) { "Expected field to contain 'subFoo' but was '$subFooField'" }

        val rootParentType = schema.getType("Foo") as GraphQLCompositeType
        val queryTraversal = ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .root(subFooField)
            .rootParentType(rootParentType)
            .coercedVariables(CoercedVariables.emptyVariables())
            .fragmentsByName(emptyMap())
            .build()

        queryTraversal.visitPreOrder(visitor)

        verifyOrder {
            // First the subFoo field itself is visited
            visitor.visitField(
                match {
                    it.field.name == "subFoo" &&
                        (it.fieldDefinition.type as GraphQLNamedType).name == "SubFoo"
                }
            )

            // Then its child (id) field is visited
            visitor.visitField(
                match {
                    it.field.name == "id" &&
                        (it.fieldDefinition.type as GraphQLScalarType).name == "String" &&
                        (it.parentType as GraphQLNamedType).name == "SubFoo"
                }
            )
        }
    }

    @Test
    fun `builder doesn't allow null arguments`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {foo { subFoo } }
            """
        )

        // Test with null schema
        assertThrows(UninitializedPropertyAccessException::class.java) {
            ViaductQueryTraverser.newQueryTraverser()
                .document(query)
                .build()
        }

        // Test with null document and no root
        assertThrows(NullPointerException::class.java) {
            ViaductQueryTraverser.newQueryTraverser()
                .schema(schema)
                .build()
        }

        // Test with root but null rootParentType
        assertThrows(NullPointerException::class.java) {
            ViaductQueryTraverser.newQueryTraverser()
                .schema(schema)
                .root(query.children[0])
                .build()
        }
    }

    @Test
    fun `builder doesn't allow ambiguous arguments`() {
        assertThrows(IllegalArgumentException::class.java) {
            ViaductQueryTraverser.newQueryTraverser()
                .document(createQuery("{foo}"))
                .operationName("foo")
                .root(Field.newField().build())
                .rootParentType(mockk<GraphQLObjectType>())
                .fragmentsByName(emptyMap())
                .build()
        }
    }

    @Test
    fun `typename special field doesn't have a fields container and throws exception`() {
        val schema = createSchema(
            """
                type Query{
                    bar: String
                }
            """
        )
        val query = createQuery(
            """
            { __typename }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)
        var capturedEnv: QueryVisitorFieldEnvironmentImpl? = null

        every {
            visitor.visitField(any())
        } answers {
            capturedEnv = firstArg()
        }

        queryTraversal.visitPreOrder(visitor)

        assertThrows(IllegalStateException::class.java) {
            capturedEnv!!.fieldsContainer
        }
    }

    @Test
    fun `traverserContext is passed along`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {foo { subFoo} bar }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        queryTraversal.visitPreOrder(visitor)

        verifyOrder {
            visitor.visitField(match { it.field.name == "foo" })
            visitor.visitField(match { it.field.name == "subFoo" })
            visitor.visitField(match { it.field.name == "bar" })
        }
    }

    @Test
    fun `traverserContext parent nodes for fragment definitions`() {
        val schema = createSchema(
            """
                type Query{
                    bar: String
                }
            """
        )
        val query = createQuery(
            """
            { ...F } fragment F on Query @myDirective {bar}
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        queryTraversal.visitPreOrder(visitor)

        verify {
            visitor.visitField(match { it.field.name == "bar" })
        }
    }

    @Test
    fun `test depthFirst`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )
        val query = createQuery(
            """
            {foo { subFoo} bar }
            """
        )

        // For depth-first traversal, we'll use a custom visitor to record the order
        val visitedFields = mutableListOf<String>()
        val depthVisitor = object : QueryVisitorStub() {
            override fun visitField(it: QueryVisitorFieldEnvironment) {
                val phase = if (it.traverserContext.phase == graphql.util.TraverserContext.Phase.ENTER) "ENTER" else "LEAVE"
                visitedFields.add("${it.field.name}-$phase")
            }
        }

        val queryTraversal = createQueryTraversal(query, schema)
        queryTraversal.visitDepthFirst(depthVisitor)

        // Verify we have the expected number of visits (6 total: 3 fields * 2 visits each)
        assertEquals(6, visitedFields.size)

        // Verify the order matches what we expect for depth-first traversal
        assertEquals("foo-ENTER", visitedFields[0])
        assertEquals("subFoo-ENTER", visitedFields[1])
        assertEquals("subFoo-LEAVE", visitedFields[2])
        assertEquals("foo-LEAVE", visitedFields[3])
        assertEquals("bar-ENTER", visitedFields[4])
        assertEquals("bar-LEAVE", visitedFields[5])
    }

    @Test
    fun `test accumulate is returned`() {
        val schema = createSchema(
            """
                type Query{
                    bar: String
                }
            """
        )
        val query = createQuery(
            """
            {bar}
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)
        val visitor = object : QueryVisitorStub() {
            override fun visitField(it: QueryVisitorFieldEnvironment) {
                it.traverserContext.setAccumulate("RESULT")
            }
        }

        val result = queryTraversal.visitDepthFirst(visitor)

        assertEquals("RESULT", result)
    }

    @Test
    fun `can select an interface field as root node`() {
        val schema = createSchema(
            """
                type Query{
                    root: SomeInterface
                }
                interface SomeInterface {
                    hello: String
                }
            """
        )
        val query = createQuery(
            """
            {root { hello } }
            """
        )

        val rootField = (query.children[0] as OperationDefinition).selectionSet.selections[0] as Field
        val hello = rootField.selectionSet.selections[0] as Field
        assertEquals("hello", hello.name)

        val rootParentType = schema.getType("SomeInterface") as GraphQLInterfaceType
        val queryTraversal = ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .root(hello)
            .rootParentType(rootParentType)
            .variables(emptyMap())
            .fragmentsByName(emptyMap())
            .build()

        queryTraversal.visitPreOrder(visitor)

        verify {
            visitor.visitField(match { it.field.name == "hello" && it.parentType == rootParentType })
        }
    }

    @Test
    fun `can select __typename field as root node`() {
        val schema = createSchema(
            """
                type Query{
                    root: SomeUnion
                }
                union SomeUnion = A | B
                type A {
                    a: String
                }
                type B {
                    b: String
                }
            """
        )
        val query = createQuery(
            """
            {root { __typename } }
            """
        )

        val rootField = (query.children[0] as OperationDefinition).selectionSet.selections[0] as Field
        val typeNameField = rootField.selectionSet.selections[0] as Field
        val rootParentType = schema.getType("SomeUnion") as GraphQLUnionType

        val queryTraversal = ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .root(typeNameField)
            .rootParentType(rootParentType)
            .variables(emptyMap())
            .fragmentsByName(emptyMap())
            .build()

        queryTraversal.visitPreOrder(visitor)

        verify {
            visitor.visitField(match { it.isTypeNameIntrospectionField })
        }
    }

    @Test
    fun `respects visitorWithControl result`() {
        val schema = createSchema(
            """
                type Query{
                    field: Foo
                }
                type Foo {
                    a: String
                }
            """
        )
        val query = createQuery(
            """
            {field { a } }
            """
        )

        val queryTraversal = ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .document(query)
            .variables(emptyMap())
            .build()

        every {
            visitor.visitFieldWithControl(any())
        } returns TraversalControl.ABORT

        queryTraversal.visitPreOrder(visitor)

        verify(exactly = 1) { visitor.visitFieldWithControl(any()) }
    }

    @Test
    fun `can cope with Scalar ObjectField visits`() {
        // Create a schema with a custom scalar
        val schema = createSchema(
            """
            scalar JSON

            type Query {
                field(arg: JSON): String
            }
            """
        )

        val query = createQuery(
            """
            {field(arg : {a : "x", b : "y"}) }
        """
        )

        val queryTraversal = createQueryTraversal(query, schema)

        // This test is verifying that the traverser doesn't throw an exception
        // when it encounters a JSON scalar with object field values
        queryTraversal.visitPreOrder(visitor)

        verify {
            visitor.visitField(
                match {
                    it.fieldDefinition.name == "field"
                }
            )
        }
    }

    @Test
    fun `directive arguments are not visited`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }

                directive @cache(
                    ttl: Int!
                ) on FIELD | FRAGMENT_SPREAD | INLINE_FRAGMENT
            """
        )
        val query = createQuery(
            """
            {foo { subFoo @cache(ttl:100) } bar @cache(ttl:200) }
            """
        )
        val queryTraversal = createQueryTraversal(query, schema)

        queryTraversal.visitPreOrder(visitor)

        verify(exactly = 0) {
            visitor.visitArgument(any())
        }
    }

    @Test
    fun `ViaductQueryTraverser always visits nodes regardless of directives`() {
        val schema = createSchema(
            """
                type Query{
                    foo: Foo
                    bar: String
                }
                type Foo {
                    subFoo: String
                }
            """
        )

        val visitedFields = mutableListOf<String>()
        val visitor = object : QueryVisitorStub() {
            override fun visitField(it: QueryVisitorFieldEnvironment) {
                visitedFields.add(it.field.name)
            }
        }

        val query = createQuery("query test { bar @include(if: false) }")
        val queryTraversal = createQueryTraversal(query, schema)

        queryTraversal.visitPreOrder(visitor)

        // ViaductQueryTraverser still visits the field unlike the original QueryTraverser
        assertTrue(
            visitedFields.contains("bar"),
            "ViaductQueryTraverser should visit 'bar' even with @include(if:false)"
        )
    }

    @Test
    fun `coerces field arguments if coerceFieldArguments is true`() {
        val schema = createSchema(
            """
                input Test {
                    x: String!
                }
                type Query {
                    testInput(input: Test!): String
                }
                type Mutation {
                    testInput(input: Test!): String
                }
            """
        )
        val query = createQuery(
            """
            mutation a(${'$'}test: Test!) {
                testInput(input: ${'$'}test)
            }
        """
        )

        var fieldArgMap: Map<String, Any> = emptyMap()
        val queryVisitorStub = object : QueryVisitorStub() {
            override fun visitField(it: QueryVisitorFieldEnvironment) {
                super.visitField(it)
                fieldArgMap = it.arguments
            }
        }

        val options = QueryTraversalOptions.defaultOptions().coerceFieldArguments(true)
        ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .document(query)
            .coercedVariables(CoercedVariables.of(mapOf("test" to mapOf("x" to "X"))))
            .options(options)
            .build()
            .visitPreOrder(queryVisitorStub)

        assertEquals(mapOf("input" to mapOf("x" to "X")), fieldArgMap)
    }

    @Test
    fun `does not coerce field arguments if coerceFieldArguments is false`() {
        val schema = createSchema(
            """
                input Test {
                    x: String!
                }
                type Query {
                    testInput(input: Test!): String
                }
                type Mutation {
                    testInput(input: Test!): String
                }
            """
        )
        val query = createQuery(
            """
            mutation a(${'$'}test: Test!) {
                testInput(input: ${'$'}test)
            }
        """
        )

        var fieldArgMap: Map<String, Any> = emptyMap()
        val queryVisitorStub = object : QueryVisitorStub() {
            override fun visitField(it: QueryVisitorFieldEnvironment) {
                super.visitField(it)
                fieldArgMap = it.arguments
            }
        }

        val options = QueryTraversalOptions.defaultOptions().coerceFieldArguments(false)
        ViaductQueryTraverser.newQueryTraverser()
            .schema(schema)
            .document(query)
            .coercedVariables(CoercedVariables.of(mapOf("test" to mapOf("x" to "X"))))
            .options(options)
            .build()
            .visitPreOrder(queryVisitorStub)

        assertEquals(emptyMap<String, Any>(), fieldArgMap)
    }
}
