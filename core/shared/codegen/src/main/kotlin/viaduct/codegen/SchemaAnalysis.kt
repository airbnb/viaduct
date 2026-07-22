package viaduct.codegen

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.string.capitalize

/**
 * Language-neutral schema analysis shared by the Java and Kotlin GRT/resolver generators.
 *
 * This is the single home for the pure, target-language-independent predicates that interpret a
 * [ViaductSchema] — node detection, BackingData detection, `@resolver` directive decoding,
 * `@idOf`/Node-id GlobalID resolution, connection/edge helpers, and tenant-module ownership.
 *
 * These were historically implemented twice: once in the Kotlin codegen's `bytecode/config` package
 * and once, hand-ported, in the Java codegen's `GraphQLSchemaParser`/`TypeMapper`. The two copies
 * had to be kept in sync by hand (the Java copy is littered with "mirrors Kotlin's X" comments).
 * Consolidating them here removes that drift risk.
 *
 * What is intentionally NOT here: anything that renders a concrete target type. The Kotlin side maps
 * to [kotlinx.metadata.KmType] (it also emits bytecode) and the Java side maps to a Java type
 * string. Each back-end derives its concrete type directly from [ViaductSchema.TypeExpr].
 *
 * Note on BackingData: [isBackingDataField] is provided as a shared predicate, but whether a
 * BackingData field is *excluded* from generation is a per-back-end policy decision (the Java GRT
 * path excludes them; the Kotlin GRT path keeps them). Call sites apply the policy; this object only
 * answers the question.
 */
object SchemaAnalysis {
    private const val NODE_INTERFACE_NAME = "Node"
    private const val ID_SCALAR_NAME = "ID"
    private const val BACKING_DATA_SCALAR_NAME = "BackingData"
    private const val RESOLVER_DIRECTIVE = "resolver"
    private const val ID_OF_DIRECTIVE = "idOf"
    private const val CONNECTION_DIRECTIVE = "connection"
    private const val EDGE_DIRECTIVE = "edge"
    private const val ONE_OF_DIRECTIVE = "oneOf"

    // ---- Node / ID -------------------------------------------------------------------------

    /**
     * True iff [typeDef] is or transitively implements the `Node` interface. Mirrors the Kotlin
     * `ViaductSchema.TypeDef.isNode` extension and the Java `isNodeType` helper.
     */
    fun isNode(typeDef: ViaductSchema.TypeDef): Boolean =
        (typeDef.name == NODE_INTERFACE_NAME && typeDef is ViaductSchema.Interface) ||
            (typeDef is ViaductSchema.OutputRecord && typeDef.supers.any { isNode(it) })

    /** True iff this is the GraphQL `ID` scalar. */
    fun isIdScalar(typeDef: ViaductSchema.TypeDef): Boolean = typeDef.kind == ViaductSchema.TypeDefKind.SCALAR && typeDef.name == ID_SCALAR_NAME

    // ---- BackingData -----------------------------------------------------------------------

    /** True iff [typeDef] is the `BackingData` scalar. */
    fun isBackingDataType(typeDef: ViaductSchema.TypeDef): Boolean = typeDef.kind == ViaductSchema.TypeDefKind.SCALAR && typeDef.name == BACKING_DATA_SCALAR_NAME

    /**
     * True iff [field]'s base type is the `BackingData` scalar. Whether such fields are excluded
     * from generation is a per-back-end policy (see the class-level note).
     */
    fun isBackingDataField(field: ViaductSchema.Field): Boolean = isBackingDataType(field.type.baseTypeDef)

    // ---- @resolver directive ---------------------------------------------------------------

    /** Decoded `@resolver` directive arguments, or null when the directive is absent. */
    data class ResolverDirectiveConfig(
        val isSelective: Boolean,
        val isBatching: Boolean,
    )

    /**
     * Decodes the `@resolver` directive on [def], or returns null if absent. Supports the legacy
     * `selective` alias for `isSelective`. Throws if either argument is present but not boolean.
     */
    fun resolverDirectiveConfigOrNull(def: ViaductSchema.Def): ResolverDirectiveConfig? {
        val directive = def.appliedDirectives.firstOrNull { it.name == RESOLVER_DIRECTIVE } ?: return null

        val selectiveArg = directive.arguments["isSelective"] ?: directive.arguments["selective"]
        val isSelective = when (selectiveArg) {
            null -> false
            is ViaductSchema.BooleanLiteral -> selectiveArg.value
            else -> error("Expected @resolver(isSelective:/selective:) to decode as a boolean on ${def.describe()}")
        }

        val batchingArg = directive.arguments["isBatching"]
        val isBatching = when (batchingArg) {
            null -> false
            is ViaductSchema.BooleanLiteral -> batchingArg.value
            else -> error("Expected @resolver(isBatching:) to decode as a boolean on ${def.describe()}")
        }

        return ResolverDirectiveConfig(isSelective = isSelective, isBatching = isBatching)
    }

    /** True for `@resolver(isSelective: true)` (or legacy `selective: true`). */
    fun isSelectiveResolver(def: ViaductSchema.Def): Boolean = resolverDirectiveConfigOrNull(def)?.isSelective ?: false

    /** True for `@resolver(isBatching: true)`. */
    fun isBatchingResolver(def: ViaductSchema.Def): Boolean = resolverDirectiveConfigOrNull(def)?.isBatching ?: false

    // ---- @idOf / GlobalID ------------------------------------------------------------------

    /**
     * The type name in an `@idOf(type:)` directive applied to [def], or null when absent.
     */
    fun idOfTypeName(def: ViaductSchema.Def): String? = idOfTypeName(def.appliedDirectives)

    private fun idOfTypeName(directives: Collection<ViaductSchema.AppliedDirective<*>>): String? {
        for (directive in directives) {
            if (directive.name == ID_OF_DIRECTIVE) {
                val typeArg = directive.arguments["type"]
                if (typeArg is ViaductSchema.StringLiteral) return typeArg.value
            }
        }
        return null
    }

    /**
     * For a field/argument, returns the `Foo` in `GlobalID<Foo>`, or null if the field should be a
     * plain `String` instead. Mirrors the Kotlin `grtNameForIdParam`:
     * - the `id` field of a Node type resolves to the containing type's name (and may not also carry `@idOf`);
     * - otherwise an `@idOf(type:)` directive supplies the name;
     * - otherwise null.
     */
    fun globalIdTargetTypeName(field: ViaductSchema.HasDefaultValue): String? {
        val containingDef = field.containingDef as? ViaductSchema.TypeDef
        val isNodeIdField = field.name == "id" && containingDef != null && isNode(containingDef)
        val idOf = idOfTypeName(field.appliedDirectives)

        return when {
            isNodeIdField -> {
                require(idOf == null) { "@idOf may not be used on the `id` field of a Node implementation" }
                containingDef!!.name
            }
            idOf != null -> idOf
            else -> null
        }
    }

    // ---- connection / edge -----------------------------------------------------------------

    /** True iff [typeDef] carries the `@connection` directive. */
    fun hasConnectionDirective(typeDef: ViaductSchema.TypeDef): Boolean = typeDef is ViaductSchema.Object && typeDef.hasAppliedDirective(CONNECTION_DIRECTIVE)

    /** True iff [typeDef] carries the `@edge` directive. */
    fun hasEdgeDirective(typeDef: ViaductSchema.TypeDef): Boolean = typeDef is ViaductSchema.Object && typeDef.hasAppliedDirective(EDGE_DIRECTIVE)

    /**
     * True iff [typeDef] is an input object carrying the `@oneOf` directive (exactly one field must
     * be set). Shared so the Java and Kotlin codegens detect `@oneOf` the same way.
     */
    fun hasOneOfDirective(typeDef: ViaductSchema.TypeDef): Boolean = typeDef is ViaductSchema.Input && typeDef.hasAppliedDirective(ONE_OF_DIRECTIVE)

    /**
     * For an `@edge` object, the name of its `node` field's base type. Throws if there is no `node`
     * field. Mirrors the Kotlin `typeOfNodeField`.
     */
    fun edgeNodeTypeName(obj: ViaductSchema.Object): String {
        val nodeField = obj.field("node")
        return checkNotNull(nodeField?.type?.baseTypeDef?.name) {
            "@edge type ${obj.name} has no `node` field."
        }
    }

    /**
     * For a `@connection` object, the Edge type name read from its `edges` field, or null when the
     * type is not a connection or has no `edges` field. Mirrors the Kotlin `connectionEdgeTypeName`.
     */
    fun connectionEdgeTypeName(obj: ViaductSchema.Object): String? {
        if (!hasConnectionDirective(obj)) return null
        val edgesField = obj.field("edges") ?: return null
        return edgesField.type.baseTypeDef.name
    }

    // ---- resolver naming conventions -------------------------------------------------------

    /**
     * The generated resolver class name for a `@resolver` field: the field name with its first
     * character upper-cased (e.g. `profile` -> `Profile`). Both the Java and Kotlin resolver
     * generators name their per-field resolver classes this way.
     */
    fun resolverClassName(fieldName: String): String = fieldName.capitalize()

    /**
     * The simple name of the generated arguments-wrapper type for a resolver field that takes
     * arguments: `${typeName}_${Capitalized field name}_Arguments` (e.g. `User_Profile_Arguments`).
     *
     * This single convention must agree across three places or generated code fails to compile: the
     * Java resolver generator, the Kotlin resolver generator, and the Kotlin GRT generator that
     * actually emits the `_Arguments` class. Centralizing it here keeps them in lockstep.
     */
    fun argumentsTypeName(
        containingTypeName: String,
        fieldName: String,
    ): String = "${containingTypeName}_${resolverClassName(fieldName)}_Arguments"

    /** [argumentsTypeName] for a resolver [field], using its containing type. */
    fun argumentsTypeName(field: ViaductSchema.Field): String = argumentsTypeName(field.containingDef.name, field.name)

    // ---- tenant-module ownership -----------------------------------------------------------

    /**
     * Extracts the tenant-module path from a build-time schema source name of the form
     * `modules/<module>/schema/...`, stripping a trailing `/src/...` segment. Returns null when the
     * source name does not match. Mirrors the Java `ownershipFilter` extraction and the Kotlin
     * `SourceLocation.tenantModule` (build-time form).
     */
    fun buildTimeTenantModule(sourceName: String): String? {
        val match = BUILD_TIME_MODULE_EXTRACTOR.find(sourceName.replace('\\', '/')) ?: return null
        val module = match.groupValues[1]
        val srcIdx = module.indexOf("/src/")
        return if (srcIdx >= 0) module.substring(0, srcIdx) else module
    }

    private val BUILD_TIME_MODULE_EXTRACTOR = Regex("modules/(.*?)/schema/")
}
