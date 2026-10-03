package viaduct.engine.runtime2.schema.lowering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.gjDef
import viaduct.graphql.schema.graphqljava.graphqlValidate
import viaduct.graphql.schema.graphqljava.viaductSchema

class OrdinarySchemaLoweringTest {
    @Test
    fun `preserves ordinary definitions roots relationships and metadata`() {
        val graphQLSource = graphQLSchema(SCHEMA)
        val source = graphQLSource.viaductSchema()
        val lowered = ViaductAndGJSchema.fromGraphQLSchema(graphQLSource).loweredSchema

        assertEquals("Read", lowered.queryTypeDef?.name)
        assertEquals("Write", lowered.mutationTypeDef?.name)
        assertEquals("Watch", lowered.subscriptionTypeDef?.name)
        assertTrue(lowered.requireType("Date") is ViaductSchema.Scalar)
        assertTrue(lowered.requireType("Status") is ViaductSchema.Enum)
        assertTrue(lowered.requireType("Filter") is ViaductSchema.Input)
        assertTrue(lowered.requireType("Choice") is ViaductSchema.Union)
        assertTrue("mark" in lowered.directives)

        val sourceDate = source.requireType("Date")
        val loweredDate = lowered.requireType("Date")
        assertEquals(sourceDate.description, loweredDate.description)
        assertEquals(
            sourceDate.appliedDirectives.semanticValues(),
            loweredDate.appliedDirectives.semanticValues(),
        )

        val sourceA = source.requireType("A")
        val loweredA = lowered.requireType("A")
        assertEquals(
            sourceA.appliedDirectives.semanticValues(),
            loweredA.appliedDirectives.semanticValues(),
        )
        assertSame(
            graphQLSource.getObjectType("A"),
            (loweredA as ViaductSchema.Object).gjDef,
        )

        val sourceChoice = source.requireType("Choice") as ViaductSchema.Union
        val loweredChoice = lowered.requireType("Choice") as ViaductSchema.Union
        assertEquals(
            sourceChoice.possibleObjectTypes.mapTo(linkedSetOf()) { it.name },
            loweredChoice.possibleObjectTypes.mapTo(linkedSetOf()) { it.name },
        )

        val sourceStatus = source.requireType("Status") as ViaductSchema.Enum
        val loweredStatus = lowered.requireType("Status") as ViaductSchema.Enum
        assertEquals(sourceStatus.description, loweredStatus.description)
        assertEquals(
            sourceStatus.values.map { it.name },
            loweredStatus.values.map { it.name },
        )
        assertEquals(
            sourceStatus.value("READY")!!.appliedDirectives.semanticValues(),
            loweredStatus.value("READY")!!.appliedDirectives.semanticValues(),
        )

        val sourceFilter = source.requireType("Filter") as ViaductSchema.Input
        val loweredFilter = lowered.requireType("Filter") as ViaductSchema.Input
        val sourceLimit = sourceFilter.field("limit")!!
        val loweredLimit = loweredFilter.field("limit")!!
        assertEquals(sourceLimit.description, loweredLimit.description)
        assertEquals(sourceLimit.defaultValue, loweredLimit.defaultValue)
        assertEquals(
            sourceLimit.appliedDirectives.semanticValues(),
            loweredLimit.appliedDirectives.semanticValues(),
        )

        val sourceField = source.requireField("A", "value")
        val loweredField = lowered.requireField("A", "value")
        assertEquals(sourceField.description, loweredField.description)
        assertEquals(
            sourceField.appliedDirectives.semanticValues(),
            loweredField.appliedDirectives.semanticValues(),
        )
        assertEquals(
            sourceField.containingExtension.sourceLocation,
            loweredField.containingExtension.sourceLocation,
        )

        val sourceDirective = source.directives.getValue("mark")
        val loweredDirective = lowered.directives.getValue("mark")
        assertEquals(sourceDirective.description, loweredDirective.description)
        assertEquals(sourceDirective.isRepeatable, loweredDirective.isRepeatable)
        assertEquals(sourceDirective.allowedLocations, loweredDirective.allowedLocations)
        assertEquals(sourceDirective.args.single().defaultValue, loweredDirective.args.single().defaultValue)
    }

    @Test
    fun `all lowered references resolve to canonical definitions and GraphQL validation succeeds`() {
        val lowered = lowerSchema(graphQLSchema(SCHEMA))

        lowered.directives.values.forEach { directive ->
            directive.args.forEach { arg ->
                assertSame(lowered.requireType(arg.type.baseTypeDef.name), arg.type.baseTypeDef)
            }
        }
        lowered.types.values.forEach { type ->
            type.appliedDirectives.forEach { applied ->
                assertSame(lowered.directives.getValue(applied.name), applied.directive)
            }
            if (type is ViaductSchema.OutputRecord) {
                type.supers.forEach { supertype ->
                    assertSame(lowered.requireType(supertype.name), supertype)
                }
            }
            if (type is ViaductSchema.Record) {
                type.fields.forEach { field ->
                    assertSame(type, field.containingDef)
                    assertSame(
                        lowered.requireType(field.type.baseTypeDef.name),
                        field.type.baseTypeDef,
                    )
                    field.args.forEach { arg ->
                        assertSame(field, arg.containingDef)
                        assertSame(
                            lowered.requireType(arg.type.baseTypeDef.name),
                            arg.type.baseTypeDef,
                        )
                    }
                }
            }
            type.possibleObjectTypes.forEach { possibleType ->
                assertSame(lowered.requireType(possibleType.name), possibleType)
            }
        }

        assertTrue(graphqlValidate(lowered).isEmpty())
    }

    private companion object {
        val SCHEMA =
            """
            schema {
              query: Read
              mutation: Write
              subscription: Watch
            }

            "Marks schema elements"
            directive @mark(label: String = "default") repeatable on
              OBJECT | FIELD_DEFINITION | INPUT_FIELD_DEFINITION

            "A date"
            scalar Date @specifiedBy(url: "https://example.com/date")

            "Current status"
            enum Status {
              READY @deprecated(reason: "Use ACTIVE")
              ACTIVE
            }

            input Filter {
              "Maximum count"
              limit: Int = 7 @mark(label: "input")
            }

            union Choice = A | B

            type A @mark(label: "object") {
              "A value"
              value(filter: Filter): Date @mark(label: "field")
            }

            type B {
              status: Status
            }

            type Read {
              choice: Choice
              status: Status
            }

            type Write {
              update(filter: Filter): Status
            }

            type Watch {
              status: Status
            }
            """
    }
}
