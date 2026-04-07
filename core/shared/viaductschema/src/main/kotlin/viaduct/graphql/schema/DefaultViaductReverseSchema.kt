package viaduct.graphql.schema

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Standard implementation of [ViaductReverseSchema] that walks the
 * schema once during construction to build a reverse index.
 */
internal class DefaultViaductReverseSchema(
    override val schema: ViaductSchema
) : ViaductReverseSchema {
    /**
     * Reverse index: target def → set of source defs that directly
     * reference it.  Identity-based because [ViaductSchema] uses
     * object identity for definition equality.
     */
    private val index: IdentityHashMap<ViaductSchema.Def, Set<ViaductSchema.Def>>

    init {
        val builder = IdentityHashMap<ViaductSchema.Def, MutableSet<ViaductSchema.Def>>()

        fun addEdge(
            target: ViaductSchema.Def,
            source: ViaductSchema.Def
        ) {
            builder.getOrPut(target) { newIdentitySet() }.add(source)
        }

        // Walk type definitions
        for (typeDef in schema.types.values) {
            // Directive applications on the type itself
            for (ad in typeDef.appliedDirectives) {
                addEdge(ad.directive, typeDef)
            }

            // Fields and their arguments (Object, Interface, Input)
            if (typeDef is ViaductSchema.Record) {
                for (field in typeDef.fields) {
                    addEdge(field.type.baseTypeDef, field)
                    for (ad in field.appliedDirectives) {
                        addEdge(ad.directive, field)
                    }
                    for (arg in field.args) {
                        addEdge(arg.type.baseTypeDef, arg)
                        for (ad in arg.appliedDirectives) {
                            addEdge(ad.directive, arg)
                        }
                    }
                }
            }

            // Implements edges (Object, Interface → Interface)
            if (typeDef is ViaductSchema.OutputRecord) {
                for (iface in typeDef.supers) {
                    addEdge(iface, typeDef)
                }
            }

            // Union membership edges (Union → Object)
            if (typeDef is ViaductSchema.Union) {
                for (obj in typeDef.possibleObjectTypes) {
                    addEdge(obj, typeDef)
                }
            }

            // Enum value directive applications
            if (typeDef is ViaductSchema.Enum) {
                for (value in typeDef.values) {
                    for (ad in value.appliedDirectives) {
                        addEdge(ad.directive, value)
                    }
                }
            }
        }

        // Walk directive definitions (directive args only —
        // GraphQL does not allow directives on directive definitions)
        for (directive in schema.directives.values) {
            for (arg in directive.args) {
                addEdge(arg.type.baseTypeDef, arg)
                for (ad in arg.appliedDirectives) {
                    addEdge(ad.directive, arg)
                }
            }
        }

        // Freeze mutable sets into unmodifiable sets
        val frozen = IdentityHashMap<ViaductSchema.Def, Set<ViaductSchema.Def>>()
        for ((key, value) in builder) {
            frozen[key] = Collections.unmodifiableSet(value)
        }
        index = frozen
    }

    override fun inboundDefs(target: ViaductSchema.Def): Collection<ViaductSchema.Def> {
        requireBelongsToSchema(target)
        return index[target] ?: emptySet()
    }

    override fun inboundFields(target: ViaductSchema.Def): Collection<ViaductSchema.Field> = inboundDefs(target).filterIsInstance<ViaductSchema.Field>()

    override fun inboundFieldArgs(target: ViaductSchema.Def): Collection<ViaductSchema.FieldArg> = inboundDefs(target).filterIsInstance<ViaductSchema.FieldArg>()

    override fun inboundDirectiveArgs(target: ViaductSchema.Def): Collection<ViaductSchema.DirectiveArg> = inboundDefs(target).filterIsInstance<ViaductSchema.DirectiveArg>()

    override fun inboundTypeDefs(target: ViaductSchema.Def): Collection<ViaductSchema.TypeDef> = inboundDefs(target).filterIsInstance<ViaductSchema.TypeDef>()

    override fun referencingTopLevelDefs(target: ViaductSchema.TopLevelDef): Collection<ViaductSchema.TopLevelDef> {
        // inboundDefs already checks affinity, but we call it below
        val result: MutableSet<ViaductSchema.TopLevelDef> = Collections.newSetFromMap(IdentityHashMap())
        for (def in inboundDefs(target)) {
            result.add(toTopLevelDef(def))
        }
        return result
    }

    private fun requireBelongsToSchema(target: ViaductSchema.Def) {
        require(containingSchemaOf(target) === schema) {
            "Target ${target.describe()} does not belong to this reverse schema's schema"
        }
    }

    companion object {
        private fun newIdentitySet(): MutableSet<ViaductSchema.Def> = Collections.newSetFromMap(IdentityHashMap())

        private fun containingSchemaOf(def: ViaductSchema.Def): ViaductSchema? =
            when (def) {
                is ViaductSchema.TypeDef -> def.containingSchema
                is ViaductSchema.Directive -> def.containingSchema
                is ViaductSchema.Field -> def.containingDef.containingSchema
                is ViaductSchema.FieldArg -> def.containingDef.containingDef.containingSchema
                is ViaductSchema.DirectiveArg -> def.containingDef.containingSchema
                is ViaductSchema.EnumValue -> def.containingDef.containingSchema
                else -> null
            }

        private fun toTopLevelDef(def: ViaductSchema.Def): ViaductSchema.TopLevelDef =
            when (def) {
                is ViaductSchema.FieldArg -> def.containingDef.containingDef
                is ViaductSchema.DirectiveArg -> def.containingDef
                is ViaductSchema.Field -> def.containingDef
                is ViaductSchema.EnumValue -> def.containingDef
                is ViaductSchema.TopLevelDef -> def
                else -> error("Unknown def type: ${def::class}")
            }
    }
}
