package viaduct.arbitrary.graphql

import graphql.language.Document
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.language.OperationDefinition.Operation
import graphql.language.SelectionSet
import graphql.language.Value
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import viaduct.arbitrary.common.Config
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.mapping.graphql.RawObject
import viaduct.mapping.graphql.RawValue
import viaduct.mapping.graphql.ValueMapper

/**
 * Generate a [graphql.language.Value] for a provided GraphQLInputType,
 * using the provided [TypeReferenceResolver] to resolve graphql-java type references
 */
fun Arb.Companion.graphQLValueFor(
    type: GraphQLInputType,
    resolver: TypeReferenceResolver,
    cfg: Config = Config.default
): Arb<Value<*>> =
    arbitrary { rs ->
        GJRawValueGen(resolver, cfg, rs)
            .map(GJRawToGJ(resolver))
            .invoke(type)
    }

/**
 * Generate a [graphql.language.Value] for a provided input type,
 * using the provided [GraphQLTypes] to resolve graphql-java type references
 */
fun Arb.Companion.graphQLValueFor(
    type: GraphQLInputType,
    types: GraphQLTypes = GraphQLTypes.empty,
    cfg: Config = Config.default
): Arb<Value<*>> = graphQLValueFor(type, TypeReferenceResolver.fromTypes(types), cfg)

/**
 * Generate a [viaduct.mapping.graphql.RawValue] for a provided input type,
 * using the provided [TypeReferenceResolver] to resolve graphql-java type references
 */
fun Arb.Companion.rawValueFor(
    type: GraphQLInputType,
    resolver: TypeReferenceResolver,
    cfg: Config = Config.default
): Arb<RawValue> =
    arbitrary { rs ->
        GJRawValueGen(resolver, cfg, rs)(type)
    }

/**
 * Generate a [RawValue] for a provided output type, returning a value that describes a possible
 * output result of that type.
 * If [type] is a [graphql.schema.GraphQLCompositeType], then [selections] must be non-null. Otherwise,
 * [selections] must be null.
 */
fun Arb.Companion.rawValueFor(
    type: GraphQLOutputType,
    selections: SelectionSet?,
    schema: GraphQLSchema,
    fragments: Map<String, FragmentDefinition> = emptyMap(),
    cfg: Config = Config.default
): Arb<RawValue> =
    arbitrary { rs ->
        val gen = GJRawValueResultGen(
            schema = schema,
            fragments = fragments,
            rs = rs,
            cfg = cfg
        )
        gen(type to selections)
    }

/**
 * Generate a [viaduct.mapping.graphql.RawObject] for a provided document, returning a value that describes
 * a possible result of executing that document against the provided [schema].
 *
 * [document] must define 1 or more operations, it must be valid for the provided schema,
 * and it may include fragment definitions.
 */
fun Arb.Companion.rawValueFor(
    document: Document,
    schema: GraphQLSchema,
    cfg: Config = Config.default
): Arb<RawObject> {
    val fragments = mutableMapOf<String, FragmentDefinition>()
    val operations = mutableListOf<OperationDefinition>()
    document.definitions.forEach {
        when (it) {
            is FragmentDefinition -> fragments[it.name] = it
            is OperationDefinition -> operations += it
        }
    }

    return Arb.element(operations).flatMap {
        val type = when (it.operation) {
            Operation.QUERY -> schema.queryType
            Operation.MUTATION ->
                checkNotNull(schema.mutationType) {
                    "mutation operation requested but schema does not define a mutation type"
                }
            Operation.SUBSCRIPTION ->
                checkNotNull(schema.subscriptionType) {
                    "subscription operation requested but schema does not define a subscription type"
                }
            null -> throw IllegalStateException("Operation can't be null")
        }
        rawValueFor(
            GraphQLNonNull.nonNull(type),
            it.selectionSet,
            schema,
            fragments.toMap(),
            cfg
        ).map { it as RawObject }
    }
}

/**
 * Generate a [RawValue] for a provided input type,
 * using the provided [GraphQLTypes] to resolve graphql-java type references
 */
fun Arb.Companion.rawValueFor(
    type: GraphQLInputType,
    types: GraphQLTypes = GraphQLTypes.empty,
    cfg: Config = Config.default
): Arb<RawValue> = rawValueFor(type, TypeReferenceResolver.fromTypes(types), cfg)

/** Generate an arbitrary [RawValue] for a provided [ViaductExtendedSchema.TypeDef] */
fun Arb.Companion.rawValueFor(
    def: ViaductExtendedSchema.TypeDef,
    cfg: Config = Config.default
): Arb<RawValue> = rawValueFor(type = def.asTypeExpr(), cfg = cfg)

/** Generate an arbitrary [RawValue] for a provided [ViaductExtendedSchema.TypeExpr] */
fun Arb.Companion.rawValueFor(
    type: ViaductExtendedSchema.TypeExpr,
    cfg: Config = Config.default
): Arb<RawValue> =
    arbitrary { rs ->
        ViaductExtendedSchemaRawValueGen(cfg, rs)(type)
    }

/**
 * Generate an arbitrary value for a provided [ViaductExtendedSchema.TypeExpr], that has
 * been mapped by the provided [viaduct.mapping.graphql.ValueMapper].
 */
fun <T> Arb.Companion.mappedValueFor(
    type: ViaductExtendedSchema.TypeExpr,
    mapper: ValueMapper<ViaductExtendedSchema.TypeExpr, RawValue, T>,
    cfg: Config = Config.default
): Arb<T> =
    rawValueFor(type, cfg).map { raw ->
        mapper(type, raw)
    }

/**
 * Generate an arbitrary value for a provided [ViaductExtendedSchema.TypeDef], that has
 * been mapped by the provided [ValueMapper].
 */
fun <T> Arb.Companion.mappedValueFor(
    def: ViaductExtendedSchema.TypeDef,
    mapper: ValueMapper<ViaductExtendedSchema.TypeExpr, RawValue, T>,
    cfg: Config = Config.default
): Arb<T> =
    mappedValueFor(
        type = def.asTypeExpr(),
        mapper = mapper,
        cfg = cfg
    )
