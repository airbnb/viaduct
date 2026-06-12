package viaduct.arbitrary.graphql

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import io.kotest.property.RandomSource
import viaduct.arbitrary.common.Config
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj

/** A catalog of configured Resolvers in a given [ViaductSchema] */
data class ResolverCoordinates(
    private val schema: ViaductSchema,
    val fieldResolvers: Set<Coordinate>,
    val nodeResolvers: Set<String>
) {
    fun plus(other: ResolverCoordinates): ResolverCoordinates {
        require(schema === other.schema)
        return copy(
            fieldResolvers = fieldResolvers + other.fieldResolvers,
            nodeResolvers = nodeResolvers + other.nodeResolvers
        )
    }

    companion object {
        operator fun invoke(
            schema: ViaductSchema,
            cfg: Config = Config.default,
            rs: RandomSource = RandomSource.default(),
        ): ResolverCoordinates =
            ResolverCoordinates(
                schema = schema,
                fieldResolvers = schema.objectCoordinates
                    .filter { coord ->
                        // include fields with @resolver directives or if sampling UndeclaredFieldResolverWeight returns true
                        val fdef = schema.schema.getFieldDefinition(coord.gj)
                        if (fdef.appliedDirectives.any { it.name == "resolver" }) {
                            true
                        } else {
                            rs.sampleWeight(cfg[UndeclaredFieldResolverWeight])
                        }
                    }
                    .toSet(),
                nodeResolvers = schema.nodeImpls
                    .filter { objName ->
                        // include objects with @resolver directives or if sampling UndeclaredNodeResolverWeight returns true
                        val objDef = schema.schema.getObjectType(objName)
                        if (objDef.appliedDirectives.any { it.name == "resolver" }) {
                            true
                        } else {
                            rs.sampleWeight(cfg[UndeclaredNodeResolverWeight])
                        }
                    }
                    .toSet(),
            )
    }

    fun fieldResolverOutputSelectionSet(coord: Coordinate): Set<Coordinate> {
        require(coord in fieldResolvers)
        return buildOutputSelectionSet(setOf(coord))
    }

    fun nodeResolverOutputSelectionSet(typeName: String): Set<Coordinate> {
        require(typeName in nodeResolvers)
        val type = schema.schema.getType(typeName)
        require(type is GraphQLObjectType)
        return buildOutputSelectionSet(
            schema.objectCoordinates(type)
                .filter { it !in fieldResolvers }
                .toSet()
        )
    }

    private fun buildOutputSelectionSet(roots: Set<Coordinate>): Set<Coordinate> {
        tailrec fun loop(
            acc: Set<Coordinate>,
            seen: Set<GraphQLCompositeType>,
            pending: Set<Coordinate>
        ): Set<Coordinate> {
            val coord = pending.firstOrNull() ?: return acc
            val fieldType = GraphQLTypeUtil.unwrapAll(
                schema.schema.getFieldDefinition(coord.gj).type
            )
            return if (fieldType.name in nodeResolvers || fieldType !is GraphQLCompositeType) {
                loop(acc + coord, seen, pending - coord)
            } else {
                val addToPending = schema.rels.possibleObjectTypes(fieldType)
                    .filter { it !in seen && it.name !in nodeResolvers }
                    .flatMap { obj ->
                        schema.objectCoordinates(obj)
                    }
                    .filter { it !in acc && it !in fieldResolvers }

                loop(
                    acc + coord,
                    seen + fieldType,
                    pending - coord + addToPending
                )
            }
        }

        return loop(emptySet(), emptySet(), roots)
    }
}
