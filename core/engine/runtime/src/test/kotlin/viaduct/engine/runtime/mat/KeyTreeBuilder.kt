package viaduct.engine.runtime.mat

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.ObjectEngineResult

class KeyTreeBuilder(private val schema: ViaductSchema) {
    private val byType = mutableMapOf<GraphQLObjectType, MutableMap<ObjectEngineResult.Key, KeyTreeBuilder>>()

    fun field(
        typeName: String,
        key: ObjectEngineResult.Key,
        buildBranch: KeyTreeBuilder.() -> Unit = {}
    ) {
        val type = checkNotNull(schema.schema.getObjectType(typeName))
        val branches = byType.getOrPut(type) { mutableMapOf() }
        branches[key] = KeyTreeBuilder(schema).also(buildBranch)
    }

    fun key(
        name: String,
        alias: String? = null,
        arguments: Map<String, Any?> = emptyMap()
    ): ObjectEngineResult.Key = ObjectEngineResult.Key(name, alias, arguments)

    fun build(): KeyTree =
        KeyTree(
            byType.mapValues { (_, branches) ->
                branches.mapValues { (_, builder) -> builder.build() }
            }
        )
}

fun KeyTree.Companion.build(
    schema: ViaductSchema,
    build: KeyTreeBuilder.() -> Unit = {}
): KeyTree = KeyTreeBuilder(schema).also(build).build()
