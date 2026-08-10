@file:Suppress("Detekt.MatchingDeclarationName")

package viaduct.arbitrary.graphql

import graphql.introspection.Introspection.DirectiveLocation
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLTypeVisitorStub
import graphql.schema.GraphQLUnionType
import graphql.schema.SchemaTransformer
import graphql.util.TraversalControl
import graphql.util.TraverserContext
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.next
import viaduct.arbitrary.common.Config
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory.DefaultDirective

/** Generate arbitrary instances of [viaduct.engine.api.ViaductSchema] from a static [Config]. */
fun Arb.Companion.viaductSchema(cfg: Config = Config.default): Arb<ViaductSchema> =
    arbitrary { rs ->
        ViaductSchemaGen(cfg, rs).gen()
    }

/** Generate arbitrary instances of [viaduct.engine.api.ViaductSchema] from a [GraphQLSchema] and [Config]. */
fun Arb.Companion.viaductSchema(
    gjSchema: GraphQLSchema,
    cfg: Config = Config.default
): Arb<ViaductSchema> =
    arbitrary { rs ->
        ViaductSchemaGen(cfg, rs).gen(gjSchema)
    }

internal class ViaductSchemaGen(val cfg: Config, val rs: RandomSource) {
    fun gen(): ViaductSchema = gen(Arb.graphQLSchema(cfg).next(rs))

    fun gen(gjSchema: GraphQLSchema): ViaductSchema {
        val transformed = gjSchema.let {
            if (cfg[UndeclaredNamespaceTypeWeight] > 0.0) {
                AddNamespaceTypes(it, cfg, rs)
            } else {
                it
            }
        }

        return ViaductSchema(transformed)
    }
}

internal object AddNamespaceTypes {
    private val directiveName = DefaultDirective.NAMESPACE_TYPE.directiveName

    operator fun invoke(
        gjSchema: GraphQLSchema,
        cfg: Config,
        rs: RandomSource
    ): GraphQLSchema {
        val operationRoots = setOfNotNull(
            gjSchema.queryType?.name,
            gjSchema.mutationType?.name,
            gjSchema.subscriptionType?.name
        )
        val objectReferences = ObjectRefs(gjSchema)
        val namespaceTypeNames = mutableSetOf<String>()

        fun walk(parent: GraphQLObjectType?) {
            if (parent == null) return

            parent.fieldDefinitions.forEach { field ->
                val target = GraphQLTypeUtil.unwrapAll(field.type) as? GraphQLObjectType
                    ?: return@forEach
                if (
                    !field.canBeNamespaceField(
                        target = target,
                        operationRoots = operationRoots,
                        objectRefs = objectReferences
                    )
                ) {
                    return@forEach
                }

                val isDeclared = target.hasAppliedDirective(directiveName)
                if (!isDeclared && !rs.sampleWeight(cfg[UndeclaredNamespaceTypeWeight])) {
                    return@forEach
                }

                if (!isDeclared) {
                    namespaceTypeNames += target.name
                }
                walk(target)
            }
        }

        walk(gjSchema.queryType)
        walk(gjSchema.mutationType)

        return addNamespaceTypes(gjSchema, namespaceTypeNames)
    }

    private fun addNamespaceTypes(
        gjSchema: GraphQLSchema,
        typeNames: Set<String>
    ): GraphQLSchema {
        if (typeNames.isEmpty()) return gjSchema

        val directiveDefinition = gjSchema.getDirective(directiveName)
            ?: GraphQLDirective
                .newDirective()
                .name(directiveName)
                .validLocation(DirectiveLocation.OBJECT)
                .build()
        val appliedDirective = directiveDefinition.toAppliedDirective()
        val schemaWithDirective = if (gjSchema.getDirective(directiveName) == null) {
            gjSchema.transform { it.additionalDirective(directiveDefinition) }
        } else {
            gjSchema
        }

        return SchemaTransformer.transformSchema(
            schemaWithDirective,
            object : GraphQLTypeVisitorStub() {
                override fun visitGraphQLObjectType(
                    node: GraphQLObjectType,
                    context: TraverserContext<GraphQLSchemaElement>
                ): TraversalControl {
                    if (node.name in typeNames) {
                        changeNode(
                            context,
                            node.transform { it.withAppliedDirective(appliedDirective) }
                        )
                    }
                    return TraversalControl.CONTINUE
                }
            }
        )
    }

    private data class ObjectRefs(
        val inboundFieldCounts: Map<String, Int>,
        val appearsInUnionsOrInterfaces: Set<String>
    ) {
        companion object {
            operator fun invoke(schema: GraphQLSchema): ObjectRefs {
                val inboundFieldCounts = mutableMapOf<String, Int>()
                val appearsInUnionsOrInterfaces = mutableSetOf<String>()

                schema.allTypesAsList.forEach { type ->
                    if (type is GraphQLFieldsContainer) {
                        type.fieldDefinitions.forEach { field ->
                            val target = GraphQLTypeUtil.unwrapAll(field.type) as? GraphQLObjectType
                            if (target != null) {
                                inboundFieldCounts[target.name] = inboundFieldCounts.getOrDefault(target.name, 0) + 1
                            }
                        }
                    }

                    when (type) {
                        is GraphQLObjectType -> {
                            if (type.interfaces.isNotEmpty()) {
                                appearsInUnionsOrInterfaces += type.name
                            }
                        }

                        is GraphQLUnionType -> type.types.mapTo(appearsInUnionsOrInterfaces) { it.name }
                    }
                }

                return ObjectRefs(
                    inboundFieldCounts = inboundFieldCounts,
                    appearsInUnionsOrInterfaces = appearsInUnionsOrInterfaces
                )
            }
        }
    }

    private fun GraphQLFieldDefinition.canBeNamespaceField(
        target: GraphQLObjectType,
        operationRoots: Set<String>,
        objectRefs: ObjectRefs
    ): Boolean =
        arguments.isEmpty() &&
            type !is GraphQLNonNull &&
            type !is GraphQLList &&
            target.name !in operationRoots &&
            objectRefs.inboundFieldCounts[target.name] == 1 &&
            target.name !in objectRefs.appearsInUnionsOrInterfaces &&
            !hasAppliedDirective(DefaultDirective.RESOLVER.directiveName)
}
