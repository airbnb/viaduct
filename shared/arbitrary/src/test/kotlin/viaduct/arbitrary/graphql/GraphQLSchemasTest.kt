@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.introspection.Introspection
import graphql.language.ArrayValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.Value
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.InputValueWithState
import graphql.schema.SchemaTraverser
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.SchemaPrinter
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.api.ViaductSchema

class GraphQLSchemasTest : KotestPropertyBase() {
    // It's useful to have a directive that can be applied to any element
    private val testDirective = GraphQLDirective
        .newDirective()
        .name("testDirective")
        .repeatable(false)
        .validLocations(*Introspection.DirectiveLocation.values())
        .build()

    @Test
    fun `Arb-graphQLSchema can generate an empty-ish schema`(): Unit =
        runBlocking {
            val cfg = Config.default + (SchemaSize to 0)
            Arb.graphQLSchema(cfg).checkAll {
                markSuccess()
            }
        }

    @Test
    fun `Arb-graphQLSchema can generate a schema from an empty GraphQLTypes`(): Unit =
        runBlocking {
            Arb.graphQLSchema(GraphQLTypes.empty).forAll {
                true
            }
        }

    @Test
    fun `Arb-graphQLSchema can generate a schema from arbitrary GraphQLTypes`(): Unit =
        runBlocking {
            val cfg = Config.default + (SchemaSize to 10)
            Arb.graphQLTypes(cfg).flatMap { types ->
                Arb.graphQLSchema(types, cfg)
            }.forAll {
                true
            }
        }

    @Test
    fun `Arb-viaductSchema can generate schemas`(): Unit =
        runBlocking {
            Arb.viaductSchema().checkAll {
                markSuccess()
            }
        }

    @Test
    fun `schema document can be roundtripped through sdl`(): Unit =
        runBlocking {
            Arb.graphQLSchema().forAll(100) { schema ->
                val sdl = SchemaPrinter().print(schema)
                SchemaParser().parse(sdl)
                true
            }
        }

    @Test
    fun `adds default values`(): Unit =
        runBlocking {
            // disabled
            Arb.graphQLSchema(Config.default + (DefaultValueWeight to 0.0))
                .forAll(100) { schema ->
                    val defaultables = CollectDefaultables(includeOneOfFields = true)
                        .also { SchemaTraverser().depthFirstFullSchema(it, schema) }
                        .defaultables

                    defaultables.none { it.isSet }
                }

            // enabled
            Arb.graphQLSchema(Config.default + (DefaultValueWeight to 1.0))
                .forAll(100) { schema ->
                    val defaultables = CollectDefaultables(includeOneOfFields = false)
                        .also { SchemaTraverser().depthFirstFullSchema(it, schema) }
                        .defaultables
                    defaultables.all { it.isSet }
                }
        }

    @Test
    fun `adds applied directives`(): Unit =
        runBlocking {
            val cfg = Config.default + (IncludeTypes to (GraphQLTypes.empty + testDirective))

            // disabled
            Arb.graphQLSchema(cfg + (AppliedDirectiveWeight to CompoundingWeight.Never))
                .forAll(100) { schema ->
                    val dirs = CollectAppliedDirectives()
                        .also { SchemaTraverser().depthFirstFullSchema(it, schema) }
                        .directives
                    dirs.isEmpty()
                }

            // enabled
            Arb.graphQLSchema(cfg + (AppliedDirectiveWeight to CompoundingWeight.Once))
                .forAll(100) { schema ->
                    val dirs = CollectAppliedDirectives()
                        .also { SchemaTraverser().depthFirstFullSchema(it, schema) }
                        .directives
                    dirs.isNotEmpty()
                }
        }

    @Test
    fun `adds applied directives -- but not on introspection elements`(): Unit =
        runBlocking {
            val cfg = Config.default +
                (AppliedDirectiveWeight to CompoundingWeight.Always) +
                (DirectiveIsRepeatable to 0.0) +
                (IncludeTypes to (GraphQLTypes.empty + testDirective))

            Arb.graphQLSchema(cfg)
                .forAll(100) { schema ->
                    val dirs = CollectAppliedDirectives()
                        .also { visitor ->
                            val introspectionRoots = listOf(
                                schema.introspectionSchemaType,
                                schema.introspectionSchemaFieldDefinition,
                                schema.introspectionTypenameFieldDefinition,
                                schema.introspectionTypeFieldDefinition,
                            )
                            introspectionRoots.forEach { elt ->
                                SchemaTraverser().depthFirst(visitor, elt)
                            }
                        }
                        .directives
                    dirs.isEmpty()
                }
        }

    @Test
    fun `generates inhabited OneOf types`(): Unit =
        runBlocking {
            // an inhabited type is a type for which it is possible to create a finite value
            // https://en.wikipedia.org/wiki/Type_inhabitation
            fun isInhabited(type: GraphQLInputObjectType): Boolean {
                fun loop(
                    seenOneOfs: Set<String>,
                    type: GraphQLInputType
                ): Boolean {
                    assert(type !is GraphQLNonNull)
                    return if (type is GraphQLInputObjectType && type.isOneOf) {
                        val checkableFields = type.fields.filter { f ->
                            if (f.type is GraphQLList) return@filter true
                            val unwrappedFieldType = GraphQLTypeUtil.unwrapAll(f.type)
                            unwrappedFieldType !is GraphQLInputObjectType || unwrappedFieldType.name !in seenOneOfs
                        }
                        checkableFields.any { f ->
                            loop(seenOneOfs + type.name, f.type)
                        }
                    } else {
                        true
                    }
                }

                return loop(emptySet(), type)
            }

            // create a configuration that is likely to produce closed OneOf subgraphs
            // this is characterized by a high generation of OneOf types with small numbers of fields
            val cfg = Config.default +
                (InputObjectTypeSize to 1.asIntRange()) +
                (TypeTypeWeights to TypeTypeWeights.zero + (TypeType.Input to 1.0)) +
                (ExplicitNullValueWeight to 0.0) +
                (OneOfTypeWeight to 1.0)

            // without default values
            Arb.graphQLSchema(cfg + (DefaultValueWeight to 0.0))
                .forAll { schema ->
                    schema.allTypesAsList
                        .mapNotNull { it as? GraphQLInputObjectType }
                        .all(::isInhabited)
                }

            // with default values
            Arb.graphQLSchema(cfg + (DefaultValueWeight to 1.0))
                .forAll { schema ->
                    schema.allTypesAsList
                        .mapNotNull { it as? GraphQLInputObjectType }
                        .all(::isInhabited)
                }
        }

    @Test
    fun `generated schemas do not have cyclic default values`(): Unit =
        runBlocking {
            // see https://spec.graphql.org/draft/#sec-Input-Object-Default-Value-Has-Cycle
            // returns true if the schema contains a default value that forms a cycle
            fun containsInvalidDefaultValues(schema: GraphQLSchema): Boolean {
                val inputTypes = schema.allTypesAsList.filterIsInstance<GraphQLInputObjectType>()

                // InputObjectDefaultValueHasCycle(inputObject, defaultValue, visitedFields)
                // returns true if this object forms a cycle
                fun objectContainsInvalidDefaultValues(
                    inputObject: GraphQLInputObjectType,
                    defaultValue: Value<*>?,
                    visitedFields: Set<GraphQLInputObjectField>
                ): Boolean {
                    if (defaultValue is ArrayValue) {
                        return defaultValue.values.any { objectContainsInvalidDefaultValues(inputObject, it, visitedFields) }
                    }
                    // An explicit null value terminates the fallthrough chain — no cycle possible.
                    // This is what makes `input A { a: A = null }` valid.
                    if (defaultValue is NullValue) return false
                    val presentFields: Map<String, Value<*>?> = when (defaultValue) {
                        null -> emptyMap() // initial call: treat as empty map
                        is ObjectValue -> defaultValue.objectFields.associate { it.name to it.value }
                        else -> return false // scalar value — no cycle possible
                    }
                    for (field in inputObject.fields) {
                        val namedFieldType =
                            GraphQLTypeUtil.unwrapAll(field.type) as? GraphQLInputObjectType ?: continue
                        if (field.name in presentFields) {
                            // Field explicitly provided in the default value object.
                            // Explicit values (including null) terminate the fallthrough chain,
                            // so we recurse without tracking visited fields.
                            // This is what makes `input A { a: A = { a: null } }` valid.
                            val fieldValue = presentFields[field.name] ?: continue
                            if (objectContainsInvalidDefaultValues(namedFieldType, fieldValue, visitedFields)) {
                                return true
                            }
                        } else {
                            // Field absent from the default value object — fall through to its schema default.
                            // This is where cycles can form: absent fields delegate to their schema default,
                            // which may itself have absent fields that delegate back.
                            if (!field.hasSetDefaultValue()) continue
                            val schemaDefault = field.inputFieldDefaultValue.value as? Value<*> ?: continue
                            if (field in visitedFields) {
                                return true
                            }
                            if (objectContainsInvalidDefaultValues(namedFieldType, schemaDefault, visitedFields + field)) {
                                return true
                            }
                        }
                    }
                    return false
                }

                val invalidInputs = inputTypes.filter { objectContainsInvalidDefaultValues(it, null, emptySet()) }
                return invalidInputs.isNotEmpty()
            }

            Arb.graphQLSchema(
                Config.default + (DefaultValueWeight to 1.0)
            ).forAll(100) { schema ->
                !containsInvalidDefaultValues(schema)
            }
        }

    @Test
    fun `generated schemas do not contain hard input cycles`(): Unit =
        runBlocking {
            val names = GraphQLNames(mapOf(TypeType.Input to setOf("A", "B")))
            val cfg = Config.default +
                (NonNullableTypeWeight to 1.0) +
                (ListTypeWeight to Never) +
                (OneOfTypeWeight to 0.0)

            val arb = arbitrary {
                val types = Arb.graphQLTypes(names, cfg).bind()
                Arb.graphQLSchema(types, cfg).bind()
            }

            arb.forAll { schema ->
                CycleGroups.mandatoryInputCycles(ViaductSchema(schema)).isEmpty()
            }
        }

    @Test
    fun `generated schemas can contain values on cyclic input objects`(): Unit =
        runBlocking {
            /**
             * Ensures that for a type like:
             *    input Inp { inp: Inp }
             * That we can generate interesting default values, like:
             *    input Inp { inp: Inp = { inp: { inp: { inp: null } } } }
             */

            val types = """
            type Query { empty:Int }
            input Inp { inp:Inp }
        """.asSchema.let { schema ->
                GraphQLTypes.empty.copy(
                    inputs = mapOf("Inp" to schema.getTypeAs("Inp"))
                )
            }

            Arb.graphQLSchema(
                types,
                Config.default +
                    (ExplicitNullValueWeight to 0.0) +
                    (MaxValueDepth to 3) +
                    (DefaultValueWeight to 1.0)
            ).forAll { schema ->
                val default = schema.getTypeAs<GraphQLInputObjectType>("Inp")
                    .getField("inp")
                    .inputFieldDefaultValue
                    .value as? ObjectValue

                default != null && default.objectFields.isNotEmpty() && default.objectFields.none { it.value is NullValue }
            }
        }

    /** This test makes no assertions but is useful for debugging. */
    @Test
    @Disabled
    fun `seed march`(): Unit =
        runBlocking {
            var seed = 0L

            while (true) {
                if (seed.mod(100) == 0) {
                    println("Seed $seed...")
                }
                try {
                    Arb.graphQLSchema().next(RandomSource.seeded(seed))
                } catch (e: Error) {
                    println("Failed on seed $seed")
                    throw e
                }
                seed += 1
            }
        }

    /** This test makes no assertions but is useful for debugging. */
    @Test
    @Disabled
    fun `dump 1 schema`(): Unit =
        runBlocking {
            val cfg = Config.default + (DescriptionLength to 0..0)
            Arb.graphQLSchema(cfg).checkAll(1) {
                val sdl = SchemaPrinter().print(it)
                println(sdl)
                markSuccess()
            }
        }

    private class CollectAppliedDirectives : GraphQLTypeVisitorStub() {
        var directives = mutableListOf<GraphQLAppliedDirective>()

        override fun visitGraphQLAppliedDirective(
            node: GraphQLAppliedDirective,
            context: TraverserContext<GraphQLSchemaElement>
        ): TraversalControl {
            directives += node
            return TraversalControl.CONTINUE
        }
    }

    private class CollectDefaultables(val includeOneOfFields: Boolean) : GraphQLTypeVisitorStub() {
        var defaultables = mutableListOf<InputValueWithState>()

        override fun visitGraphQLArgument(
            node: GraphQLArgument,
            context: TraverserContext<GraphQLSchemaElement>
        ): TraversalControl {
            defaultables += node.argumentDefaultValue
            return TraversalControl.CONTINUE
        }

        override fun visitGraphQLInputObjectField(
            node: GraphQLInputObjectField,
            context: TraverserContext<GraphQLSchemaElement>
        ): TraversalControl {
            val parent = context.parentNode
            if (parent is GraphQLInputObjectType && parent.isOneOf && !includeOneOfFields) {
                return TraversalControl.CONTINUE
            }
            defaultables += node.inputFieldDefaultValue
            return TraversalControl.CONTINUE
        }

        override fun visitGraphQLDirective(
            node: GraphQLDirective,
            context: TraverserContext<GraphQLSchemaElement>
        ): TraversalControl {
            // skip built-in directives, some of which define arguments with default values that are outside the control of this generator
            if (node.name in builtinDirectives) {
                return TraversalControl.ABORT
            }
            return TraversalControl.CONTINUE
        }

        override fun visitGraphQLType(
            node: GraphQLSchemaElement,
            context: TraverserContext<GraphQLSchemaElement>
        ): TraversalControl =
            // skip introspection fields and types, which are outside the control of this generator
            if (node is GraphQLNamedType && node.name.startsWith("__")) {
                TraversalControl.ABORT
            } else {
                TraversalControl.CONTINUE
            }
    }
}
