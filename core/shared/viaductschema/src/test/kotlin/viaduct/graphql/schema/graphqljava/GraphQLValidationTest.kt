package viaduct.graphql.schema.graphqljava

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.builder.InterfaceTypeBuilder
import viaduct.graphql.schema.builder.ObjectTypeBuilder
import viaduct.graphql.schema.builder.OutputFieldBuilder
import viaduct.graphql.schema.builder.TypeExprBuilder
import viaduct.graphql.schema.builder.ViaductSchemaBuilder

class GraphQLValidationTest {
    @Test
    fun `valid schema has no errors`() {
        val schema =
            ViaductSchemaBuilder()
                .addDefinition(
                    ObjectTypeBuilder("Query")
                        .addField(OutputFieldBuilder("value", TypeExprBuilder("String")))
                ).build()

        assertTrue(graphqlValidate(schema).isEmpty())
    }

    @Test
    fun `schema without operation roots has no registry errors`() {
        val schema =
            ViaductSchemaBuilder(queryTypeName = null)
                .addDefinition(
                    ObjectTypeBuilder("Query")
                        .addField(OutputFieldBuilder("value", TypeExprBuilder("String")))
                ).build()

        assertTrue(graphqlValidate(schema).isEmpty())
    }

    @Test
    fun `custom operation root names are preserved`() {
        val schema =
            ViaductSchemaBuilder(
                queryTypeName = "RootQuery",
                mutationTypeName = "RootMutation",
                subscriptionTypeName = "RootSubscription",
            ).addDefinition(
                ObjectTypeBuilder("RootQuery")
                    .addField(OutputFieldBuilder("value", TypeExprBuilder("String")))
            ).addDefinition(
                ObjectTypeBuilder("RootMutation")
                    .addField(OutputFieldBuilder("setValue", TypeExprBuilder("String")))
            ).addDefinition(
                ObjectTypeBuilder("RootSubscription")
                    .addField(OutputFieldBuilder("valueChanged", TypeExprBuilder("String")))
            ).build()

        assertTrue(graphqlValidate(schema).isEmpty())
    }

    @Test
    fun `invalid schema returns graphql-java errors`() {
        val schema =
            ViaductSchemaBuilder()
                .addDefinition(
                    InterfaceTypeBuilder("Node")
                        .addField(OutputFieldBuilder("id", TypeExprBuilder("ID", nullable = false)))
                ).addDefinition(
                    ObjectTypeBuilder("Query")
                        .addInterface("Node")
                        .addField(OutputFieldBuilder("value", TypeExprBuilder("String")))
                )
                .build()

        assertTrue(graphqlValidate(schema).isNotEmpty())
    }
}
