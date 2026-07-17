package viaduct.tenant.codegen.bytecode.config

import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmVariance
import kotlinx.metadata.isNullable
import viaduct.apiannotations.VisibleForTest
import viaduct.codegen.SchemaAnalysis
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name
import viaduct.graphql.schema.ViaductReverseSchema
import viaduct.graphql.schema.ViaductSchema

// Utilities expressed as extension functions go here.  Constants and
// other utility functions go in Config.kt

/**
 * Convert a GraphQL type into a Kotlin type-expression for its GRT.
 *
 * @param pkg is the package into which GRTs are being generated.
 * @param isInput
 *   when true, if [this.type] has one or more list-wrappers, then the
 *   kotlin types for the list's type-parameters will be wildcards with
 *   an upperbound of the indicated GRT (or, in the case of nested lists,
 *   an upper-bound of the inner-list type).
 *   See learnings.md for more
 * @param useSchemaValueType
 *   when true, v1 objects definitions will be represented using their
 *   Value class
 */
fun ViaductSchema.HasDefaultValue.kmType(
    pkg: KmName,
    baseTypeMapper: BaseTypeMapper,
    isInput: Boolean = false,
    useSchemaValueType: Boolean = false
): KmType = type.kmType(pkg, baseTypeMapper, this, isInput, useSchemaValueType)

fun ViaductSchema.TypeExpr<*>.kmType(
    pkg: KmName,
    baseTypeMapper: BaseTypeMapper,
    field: ViaductSchema.HasDefaultValue?,
    isInput: Boolean,
    useSchemaValueType: Boolean
): KmType {
    var result = this.baseTypeKmType(pkg, baseTypeMapper, field, isInput)

    if (useSchemaValueType) {
        val baseType = this.baseTypeDef
        if (!this.isList &&
            baseType is ViaductSchema.Object &&
            !baseType.isConnection &&
            !baseType.isNode &&
            !cfg.nativeGraphQLTypeToKmName(baseTypeMapper).containsKey(baseType.name)
        ) {
            val baseTypeName = "$pkg/${baseType.name}"
            result = KmType().also {
                it.classifier = KmClassifier.Class("$baseTypeName.Value")
                it.isNullable = this.baseTypeNullable
            }
        }
    }

    // For input types, the variance of subclassable GRTs is OUT, the rest are INVARIANT
    var variance =
        if (!isInput) {
            KmVariance.INVARIANT
        } else {
            when (this.baseTypeDef.kind) {
                ViaductSchema.TypeDefKind.OBJECT -> {
                    baseTypeMapper.getInputVarianceForObject() ?: KmVariance.INVARIANT
                }

                ViaductSchema.TypeDefKind.ENUM, ViaductSchema.TypeDefKind.INTERFACE, ViaductSchema.TypeDefKind.UNION
                -> KmVariance.OUT

                ViaductSchema.TypeDefKind.SCALAR -> {
                    if (result.name == Km.ANY || result.name == baseTypeMapper.getGlobalIdType().asKmName) {
                        // JSON types map to kotlin/Any
                        KmVariance.OUT
                    } else {
                        KmVariance.INVARIANT
                    }
                }

                else -> KmVariance.INVARIANT
            }
        }
    for (i in (this.listDepth - 1) downTo 0) {
        result = KmType().also {
            it.classifier = KmClassifier.Class(Km.LIST.toString())
            it.arguments.add(KmTypeProjection(variance, result))
            it.isNullable = this.nullableAtDepth(i)
        }
        // For input types, all contained-lists have (upper-bounded) wildcard types
        variance = if (isInput) KmVariance.OUT else KmVariance.INVARIANT
    }
    return result
}

/** return a KmType describing this HasDefaultValue's base (unwrapped) type */
fun ViaductSchema.HasDefaultValue.baseTypeKmType(
    pkg: KmName,
    baseTypeMapper: BaseTypeMapper
): KmType = this.type.baseTypeKmType(pkg, baseTypeMapper, this, false)

/** return a KmType describing this TypeExpr's base (unwrapped) type */
fun ViaductSchema.TypeExpr<*>.baseTypeKmType(
    pkg: KmName,
    baseTypeMapper: BaseTypeMapper,
    field: ViaductSchema.HasDefaultValue?,
    isInput: Boolean,
): KmType {
    // Check if mapper wants to handle this type
    baseTypeMapper.mapBaseType(this, pkg, field, isInput)?.let { return it }

    // Default case - create standard KmType
    val kmName = cfg.nativeGraphQLTypeToKmName(baseTypeMapper)[this.baseTypeDef.name]
        ?: KmName("$pkg/${this.baseTypeDef.name}")

    return KmType().also {
        it.classifier = KmClassifier.Class(kmName.toString())
        it.isNullable = this.baseTypeNullable
    }
}

// *** HasDefault-related utilities *** //

/**
 * Returns true iff [ViaductSchema.HasDefaultValue.viaductDefaultValue]
 * would _not_ throw an exception. (Public for test generator.)
 */
@VisibleForTest
val ViaductSchema.HasDefaultValue.hasViaductDefaultValue get() = type.isNullable

/**
 * For GraphQL input-type and argument defs, returns the default value the generated constructor
 * should assume for a field.  Based on historical Viaduct behavior, this returns
 * null for nullable fields (even if the GraphQL schema has another default value
 * for the field), and throws an exception otherwise.
 *
 * For fields of GraphQL output types, this returns the default value that the type's
 * Value-class constructor should assume for a field.  This returns the empty
 * list for fields that have a list-type (even if the field is nullable, null for
 * non-list fields that are nullable, and throws an exception otherwise.
 */
val ViaductSchema.HasDefaultValue.viaductDefaultValue: Any?
    get() {
        if (!type.isNullable) throw NoSuchElementException("No default value for ${this.describe()}")
        return if (containingDef is ViaductSchema.Object && type.isList) {
            emptyList<Any?>()
        } else {
            null
        }
    }

// *** TypeDef-related utilities *** //

fun ViaductSchema.TypeDef.hashForSharding(): Int {
    val h = name.hashCode()
    return if (0 <= h) h else -h
}

@VisibleForTest
fun ViaductSchema.Object.isEligible(baseTypeMapper: BaseTypeMapper): Boolean = isEligible(baseTypeMapper, schema = null)

fun ViaductSchema.Object.isEligible(
    baseTypeMapper: BaseTypeMapper,
    schema: ViaductSchema?
): Boolean {
    // Root types (Query, Mutation, Subscription) are always eligible
    if (schema != null) {
        if (this === schema.queryTypeDef || this === schema.mutationTypeDef || this === schema.subscriptionTypeDef) {
            return true
        }
    } else {
        // When schema is not provided, check against conventional and likely custom root type patterns
        // This handles both standard names (Query, Mutation, Subscription) and custom names that are
        // actual root types in schemas with custom schema definitions
        if (name in setOf("Query", "Mutation", "Subscription")) {
            return true
        }
    }

    // PagedConnection types are generated via Kotlin src codegen (ConnectionTypeGenerator)
    return !isPagedConnection &&
        !cfg.nativeGraphQLTypeToKmName(baseTypeMapper).containsKey(name)
}

// Internal for test generators
val ViaductSchema.TypeDef.isPagedConnection
    get() =
        this is ViaductSchema.Object && supers.any { it.name == "PagedConnection" }

/**
 * GraphQL allows implementing interfaces and object-types to add
 * arguments to fields where the parent has none.  This is a feature
 * we can't support in Kotlin, so here we're scanning for this use case.
 */
@VisibleForTest
fun ViaductSchema.Interface.noArgsAnywhere(fieldName: String): Boolean {
    if (this.field(fieldName)!!.hasArgs) return false

    for (objType in this.possibleObjectTypes) {
        if (objType.field(fieldName)!!.hasArgs) return false
    }
    return true
}

/**
 * True if this type implements the Node interface.
 * Delegates to the shared language-neutral [SchemaAnalysis.isNode].
 */
val ViaductSchema.TypeDef.isNode: Boolean
    get() = SchemaAnalysis.isNode(this)

/**
 * True is this type implements the PagedConnection interface,
 * either directly or indirectly.
 */
val ViaductSchema.TypeDef.isConnection: Boolean
    get() = (name == "PagedConnection" && this is ViaductSchema.Interface) ||
        (this is ViaductSchema.Interface && supers.any { it.isConnection }) ||
        (this is ViaductSchema.Object && supers.any { it.isConnection })

/** True if this type has a Reflection object generated for it */
val ViaductSchema.TypeDef.hasReflectedType: Boolean
    // scalar types do not support reflection because they are outside the GRT model
    get() = this !is ViaductSchema.Scalar

/** True if this type emits a `Fields` object (only records and unions have one). */
val ViaductSchema.TypeDef.hasFieldsObject: Boolean
    get() = this is ViaductSchema.Union || this is ViaductSchema.Record

/** The fields that get a reflected [viaduct.api.reflect.Field] descriptor: only [ViaductSchema.Record]s have them. */
val ViaductSchema.TypeDef.reflectedFields: Iterable<ViaductSchema.HasDefaultValue>
    get() = (this as? ViaductSchema.Record)?.fields ?: emptyList()

/**
 * True if a field on a root type should emit [RootObjectField] instead of [CompositeField].
 * Requires: the containing type is a root type, the field's return type is an object type
 * and the field is not list-wrapped.
 */
fun ViaductSchema.Field.isRootObjectFieldEligible(pathToParentObject: List<String>?): Boolean = pathToParentObject != null && !type.isList && type.baseTypeDef.kind == ViaductSchema.TypeDefKind.OBJECT

/**
 * Returns the field path from the root query type to this root type:
 * - root query itself: returns `emptyList()`
 * - `@namespaceType` reachable from root query (e.g., `Bar` via `Query.foo.bar`):
 *   returns the path (e.g. `["foo", "bar"]`)
 * - non-root type or `@namespaceType` unreachable from root query: returns `null`
 *
 * Uses [ViaductReverseSchema.inboundFields] to walk backwards. The `NamespaceTypeConstraintsRule`
 * guarantees each namespace type is referenced by exactly one field, and thus references are acyclical.
 */
fun ViaductSchema.TypeDef.pathFromQueryRoot(
    reverseSchema: ViaductReverseSchema,
    queryTypeDef: ViaductSchema.Object?
): List<String>? {
    if (queryTypeDef == null) return null
    val fields = mutableListOf<String>()
    var current: ViaductSchema.TypeDef = this
    while (current !== queryTypeDef) {
        if (current !is ViaductSchema.Object || !current.hasAppliedDirective("namespaceType")) return null
        val inboundFields = reverseSchema.inboundFields(current)
        check(inboundFields.size == 1) {
            "Expected exactly one inbound field for namespace type '${current.name}', " +
                "found ${inboundFields.size}"
        }
        val parentField = inboundFields.single()
        fields.add(parentField.name)
        current = parentField.containingDef
    }
    return fields.reversed()
}

val ViaductSchema.SourceLocation.tenantModule: String?
    get() = this.sourceName.let {
        // Normalize Windows backslashes so the slash-only moduleExtractor regex matches on every OS.
        cfg.moduleExtractor.find(it.replace('\\', '/'))?.groups?.get(1)?.value
    }?.substringBefore("/src/")

/**
 * True if this type has the @edge directive applied.
 * Delegates to the shared language-neutral [SchemaAnalysis.hasEdgeDirective].
 */
val ViaductSchema.TypeDef.hasEdgeDirective: Boolean
    get() = SchemaAnalysis.hasEdgeDirective(this)

/**
 * For an Edge type, returns the type of the node field.
 * Delegates to the shared language-neutral [SchemaAnalysis.edgeNodeTypeName].
 * @return The name of the Node type or will throw error if it doesn't exist.
 */
val ViaductSchema.Object.typeOfNodeField: String
    get() = SchemaAnalysis.edgeNodeTypeName(this)

/**
 * True if this type has the @connection directive applied.
 * Delegates to the shared language-neutral [SchemaAnalysis.hasConnectionDirective].
 */
val ViaductSchema.TypeDef.hasConnectionDirective: Boolean
    get() = SchemaAnalysis.hasConnectionDirective(this)

val ViaductSchema.Def.isSelectiveResolver: Boolean
    get() = SchemaAnalysis.isSelectiveResolver(this)

val ViaductSchema.Def.isBatchingResolver: Boolean
    get() = SchemaAnalysis.isBatchingResolver(this)

/**
 * For a Connection type, extracts the Edge type name from the edges field.
 * Delegates to the shared language-neutral [SchemaAnalysis.connectionEdgeTypeName].
 *
 * @return The name of the Edge type, or null if this is not a Connection type
 *         or the edges field is not found
 */
val ViaductSchema.Object.connectionEdgeTypeName: String?
    get() = SchemaAnalysis.connectionEdgeTypeName(this)
