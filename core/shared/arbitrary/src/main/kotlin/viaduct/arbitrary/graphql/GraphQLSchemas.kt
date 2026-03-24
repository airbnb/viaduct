@file:Suppress("Detekt.MatchingDeclarationName")

package viaduct.arbitrary.graphql

import graphql.Directives
import graphql.Scalars
import graphql.TypeResolutionEnvironment
import graphql.introspection.Introspection.DirectiveLocation
import graphql.language.Value
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.GraphQLUnionType
import graphql.schema.GraphQLUnmodifiedType
import graphql.schema.SchemaTransformer
import graphql.schema.TypeResolver
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaParser
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import viaduct.arbitrary.common.Config
import viaduct.engine.api.ViaductSchema
import viaduct.mapping.graphql.GJValueConv

/** Generate arbitrary [GraphQLSchema]s from a static [Config] */
fun Arb.Companion.graphQLSchema(cfg: Config = Config.default): Arb<GraphQLSchema> =
    Arb.graphQLTypes(cfg).flatMap { types ->
        graphQLSchema(types, cfg)
    }

/** Generate arbitrary [ViaductSchema]s from a static [Config] */
fun Arb.Companion.viaductSchema(cfg: Config = Config.default): Arb<ViaductSchema> = graphQLSchema(cfg).map(::ViaductSchema)

/** Generate arbitrary [GraphQLSchema]s from a [GraphQLTypes] */
@JvmName("arbGraphQLSchema")
fun Arb.Companion.graphQLSchema(
    types: GraphQLTypes,
    cfg: Config = Config.default
): Arb<GraphQLSchema> =
    arbitrary { rs ->
        SchemaGenerator(cfg, rs).createSchema(types)
    }

/** Methods for generating GraphQLSchema instances */
internal class SchemaGenerator(val cfg: Config, val rs: RandomSource) {
    private val emptyQuery: GraphQLObjectType =
        GraphQLObjectType
            .newObject()
            .name("EmptyQuery")
            .field(
                GraphQLFieldDefinition
                    .newFieldDefinition()
                    .name("placeholder")
                    .type(Scalars.GraphQLInt)
                    .build()
            ).build()

    private fun codeRegistry(types: GraphQLTypes): GraphQLCodeRegistry {
        val noopResolver =
            object : TypeResolver {
                override fun getType(env: TypeResolutionEnvironment?): GraphQLObjectType = throw UnsupportedOperationException("not implemented")
            }

        return GraphQLCodeRegistry
            .newCodeRegistry()
            .also {
                types.interfaces.forEach { (name, _) ->
                    it.typeResolver(name, noopResolver)
                }
                types.unions.forEach { (name, _) ->
                    it.typeResolver(name, noopResolver)
                }
            }.build()
    }

    /** Generate a GraphQLSchema from a GraphQLTypes */
    fun createSchema(types: GraphQLTypes): GraphQLSchema {
        val queryType = types.objects.entries.firstOrNull()?.value ?: emptyQuery
        return GraphQLSchema
            .newSchema()
            .query(queryType)
            .additionalTypes(types.interfaces.values.toSet())
            .additionalTypes((types.objects.values.toSet() - queryType))
            .additionalTypes(types.inputs.values.toSet())
            .additionalTypes(types.unions.values.toSet())
            .additionalTypes(types.scalars.values.toSet())
            .additionalTypes(types.enums.values.toSet())
            .additionalDirectives(types.directives.values.toSet())
            .codeRegistry(codeRegistry(types))
            .build()
            .let(::finalize)
    }

    fun createSchema(sdl: String): GraphQLSchema {
        val tdr = SchemaParser().parse(sdl)
        return graphql.schema.idl
            .SchemaGenerator()
            .makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
    }

    fun finalize(schema: GraphQLSchema): GraphQLSchema =
        schema
            .let {
                val vschema = ViaductSchema(it)
                // It's hard to generate default values at the same time that we're generating types.
                // Generate default values as a separate pass
                if (cfg[DefaultValueWeight] > 0.0) {
                    SchemaTransformer.transformSchema(it, AddDefaults(vschema, cfg, rs))
                } else {
                    it
                }
            }
            .let {
                val vschema = ViaductSchema(it)
                SchemaTransformer.transformSchema(it, AddAppliedDirectives(vschema, cfg, rs))
            }
}

internal class AddDefaults(private val schema: ViaductSchema, private val cfg: Config, private val rs: RandomSource) : GraphQLTypeVisitorStub() {
    /**
     * Default values in a schema have different requirements than those used in a Document.
     *
     * For example, consider this schema with invalid default values:
     *    input A { b:B = {} }
     *    input B { a:A = {} }
     *
     * These definitions break the inhabitation property of the schema because their coerced
     * default values are infinitely large.
     *
     * For the purposes of generating a default value in a schema, we need our graph of type cycles
     * to include every possible edge, even edges from nullable or list-typed fields.
     */
    private val cycleGroups = CycleGroups.allInputCycles(schema)

    // don't traverse into built-in directives
    override fun visitGraphQLDirective(
        node: GraphQLDirective,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        if (node.name in builtinDirectives) {
            TraversalControl.ABORT
        } else {
            TraversalControl.CONTINUE
        }

    override fun visitGraphQLInputObjectField(
        field: GraphQLInputObjectField,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl {
        // don't add default values for one of fields
        val parent = context.parentNode
        if (parent is GraphQLInputObjectType && parent.isOneOf) {
            return TraversalControl.CONTINUE
        }

        if (rs.sampleWeight(cfg[DefaultValueWeight])) {
            changeNode(
                context,
                field.transform {
                    it.defaultValueLiteral(genDefaultValue(field.type))
                }
            )
        }

        return TraversalControl.CONTINUE
    }

    override fun visitGraphQLArgument(
        arg: GraphQLArgument,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl {
        if (rs.sampleWeight(cfg[DefaultValueWeight])) {
            changeNode(
                context,
                arg.transform {
                    it.defaultValueLiteral(genDefaultValue(arg.type))
                }
            )
        }

        return TraversalControl.CONTINUE
    }

    private fun genDefaultValue(type: GraphQLInputType): Value<*> =
        Arb.ir(schema, cycleGroups, type, cfg).map { ir ->
            GJValueConv(type).invert(ir)
        }.next(rs)
}

internal class AddAppliedDirectives(private val schema: ViaductSchema, private val cfg: Config, private val rs: RandomSource) : GraphQLTypeVisitorStub() {
    private val directivesByLocation =
        schema.schema.directives.fold(emptyMap<DirectiveLocation, Set<GraphQLDirective>>()) { acc, dir ->
            // @oneOf is applied during type generation, we can skip applying it here
            if (dir.name == Directives.OneOfDirective.name) {
                acc
            } else {
                dir.validLocations().fold(acc) { acc, loc ->
                    val newDirs = (acc[loc] ?: emptySet()) + dir
                    acc + (loc to newDirs)
                }
            }
        }

    // don't traverse into built-in directives
    override fun visitGraphQLDirective(
        node: GraphQLDirective,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        if (node.name in builtinDirectives) {
            TraversalControl.ABORT
        } else {
            TraversalControl.CONTINUE
        }

    override fun visitGraphQLArgument(
        node: GraphQLArgument,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(
            context,
            DirectiveLocation.ARGUMENT_DEFINITION,
            // non-nullable arguments may not be deprecated
            allowDeprecated = node.type !is GraphQLNonNull
        ) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    override fun visitGraphQLObjectType(
        node: GraphQLObjectType,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(context, DirectiveLocation.OBJECT) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    override fun visitGraphQLFieldDefinition(
        node: GraphQLFieldDefinition,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(context, DirectiveLocation.FIELD_DEFINITION) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    override fun visitGraphQLInterfaceType(
        node: GraphQLInterfaceType,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(context, DirectiveLocation.INTERFACE) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    override fun visitGraphQLUnionType(
        node: GraphQLUnionType,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(context, DirectiveLocation.UNION) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    override fun visitGraphQLEnumType(
        node: GraphQLEnumType,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(context, DirectiveLocation.ENUM) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    override fun visitGraphQLEnumValueDefinition(
        node: GraphQLEnumValueDefinition,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(context, DirectiveLocation.ENUM_VALUE) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    override fun visitGraphQLInputObjectType(
        node: GraphQLInputObjectType,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(context, DirectiveLocation.INPUT_OBJECT) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    override fun visitGraphQLInputObjectField(
        node: GraphQLInputObjectField,
        context: TraverserContext<GraphQLSchemaElement>
    ): TraversalControl =
        replaceAppliedDirectives(
            context,
            DirectiveLocation.INPUT_FIELD_DEFINITION,
            // non-nullable input fields may not be deprecated
            allowDeprecated = node.type !is GraphQLNonNull
        ) { dirs ->
            node.transform {
                it.replaceAppliedDirectives(dirs)
            }
        }

    private fun <T : GraphQLDirectiveContainer> replaceAppliedDirectives(
        ctx: TraverserContext<GraphQLSchemaElement>,
        loc: DirectiveLocation,
        allowDeprecated: Boolean = true,
        doReplace: (dirs: List<GraphQLAppliedDirective>) -> T
    ): TraversalControl =
        ctx.unlessIntrospection {
            val dirs = genAppliedDirectives(loc, ctx.collectTraversedDirectives(), allowDeprecated)
            if (dirs.isEmpty()) {
                TraversalControl.CONTINUE
            } else {
                changeNode(ctx, doReplace(dirs))
            }
        }

    private fun genAppliedDirectives(
        loc: DirectiveLocation,
        traversedDirectives: Set<String>,
        allowDeprecated: Boolean
    ): List<GraphQLAppliedDirective> {
        tailrec fun loop(
            acc: List<GraphQLAppliedDirective>,
            pool: Set<GraphQLDirective>
        ): List<GraphQLAppliedDirective> =
            if (pool.isNotEmpty() && acc.size != cfg[AppliedDirectiveWeight].max && rs.sampleWeight(cfg[AppliedDirectiveWeight].weight)) {
                val dir = Arb.of(pool).next(rs)
                val applied = dir
                    .toAppliedDirective()
                    .transform {
                        val args = dir.arguments.map { arg ->
                            val value = Arb.ir(schema, arg.type, cfg)
                                .map { ir -> GJValueConv(arg.type).invert(ir) }
                                .next(rs)
                            arg.toAppliedArgument().transform { b ->
                                b.valueLiteral(value)
                                // to placate graphql-java's type-consistency checker, set the type to a GraphQLTypeReference
                                b.type(arg.type.asReference)
                            }
                        }
                        it.replaceArguments(args)
                    }
                loop(
                    acc = acc + applied,
                    pool = if (dir.isRepeatable) pool else (pool - dir)
                )
            } else {
                acc
            }

        val pool = (directivesByLocation[loc] ?: emptySet())
            .let { pool ->
                // some locations don't allow application of @deprecated.
                // Remove it from the pool if necessary
                if (!allowDeprecated) {
                    pool.filterNot { it.name == Directives.DeprecatedDirective.name }.toSet()
                } else {
                    pool
                }
            }
            .let { pool ->
                // In order to prevent cycles of directive application, remove any directives that
                // have already been applied in our traversal path.
                if (traversedDirectives.isNotEmpty()) {
                    pool.filter { it.name !in traversedDirectives }.toSet()
                } else {
                    pool
                }
            }
        return loop(emptyList(), pool.toSet())
    }

    private val GraphQLInputType.asReference: GraphQLInputType
        get() =
            when (this) {
                is GraphQLList ->
                    GraphQLList((this.wrappedType as GraphQLInputType).asReference)
                is GraphQLNonNull ->
                    GraphQLNonNull((this.wrappedType as GraphQLInputType).asReference)
                is GraphQLTypeReference -> this
                is GraphQLUnmodifiedType -> GraphQLTypeReference(name)
                else -> throw IllegalArgumentException("Unsupoprted type: $this")
            }

    private fun TraverserContext<GraphQLSchemaElement>.unlessIntrospection(fn: () -> TraversalControl): TraversalControl {
        val node = thisNode()
        if (node is GraphQLNamedType && node.name.startsWith("__")) {
            return TraversalControl.ABORT
        }
        if (node is GraphQLFieldDefinition && node.name.startsWith("__")) {
            return TraversalControl.ABORT
        }
        return fn()
    }

    private fun TraverserContext<GraphQLSchemaElement>.collectTraversedDirectives(): Set<String> = parentNodes.mapNotNull { (it as? GraphQLDirective)?.name }.toSet()
}
