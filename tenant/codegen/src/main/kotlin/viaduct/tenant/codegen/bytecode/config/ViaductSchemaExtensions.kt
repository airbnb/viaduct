package viaduct.tenant.codegen.bytecode.config

import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmVariance
import kotlinx.metadata.isNullable
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.codegen.utils.name
import viaduct.graphql.schema.ViaductExtendedSchema

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
fun ViaductExtendedSchema.HasDefaultValue.kmType(
    pkg: KmName,
    baseTypeMapper: BaseTypeMapper,
    isInput: Boolean = false,
    useSchemaValueType: Boolean = false
): KmType = type.kmType(pkg, baseTypeMapper, this, isInput, useSchemaValueType)

fun ViaductExtendedSchema.TypeExpr.kmType(
    pkg: KmName,
    baseTypeMapper: BaseTypeMapper,
    field: ViaductExtendedSchema.HasDefaultValue?,
    isInput: Boolean,
    useSchemaValueType: Boolean
): KmType {
    var result = this.baseTypeKmType(pkg, baseTypeMapper, field, isInput)

    if (useSchemaValueType) {
        val baseType = this.baseTypeDef
        if (!this.isList &&
            baseType is ViaductExtendedSchema.Object &&
            !baseType.isConnection &&
            !baseType.isNode &&
            !cfg.nativeGraphQLTypeToKmName(baseTypeMapper).containsKey(baseType.name)
        ) {
            val baseTypeName = "$pkg/${baseType.name}"
            return KmType().also {
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
                ViaductExtendedSchema.TypeDefKind.OBJECT -> {
                    baseTypeMapper.getInputVarianceForObject() ?: KmVariance.INVARIANT
                }

                ViaductExtendedSchema.TypeDefKind.ENUM, ViaductExtendedSchema.TypeDefKind.INTERFACE, ViaductExtendedSchema.TypeDefKind.UNION
                -> KmVariance.OUT

                ViaductExtendedSchema.TypeDefKind.SCALAR -> {
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
fun ViaductExtendedSchema.HasDefaultValue.baseTypeKmType(
    pkg: KmName,
    baseTypeMapper: BaseTypeMapper
): KmType = this.type.baseTypeKmType(pkg, baseTypeMapper, this)

/** return a KmType describing this TypeExpr's base (unwrapped) type */
fun ViaductExtendedSchema.TypeExpr.baseTypeKmType(
    pkg: KmName,
    baseTypeMapper: BaseTypeMapper,
    field: ViaductExtendedSchema.HasDefaultValue?,
    isInput: Boolean = false,
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
 * Returns true iff [ViaductExtendedSchema.HasDefaultValue.viaductDefaultValue]
 * would _not_ throw an exception.  (Public for test generator.)
 */
val ViaductExtendedSchema.HasDefaultValue.hasViaductDefaultValue get() = type.isNullable

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
val ViaductExtendedSchema.HasDefaultValue.viaductDefaultValue: Any?
    get() {
        if (!type.isNullable) throw NoSuchElementException("No default value for ${this.describe()}")
        return if (containingDef is ViaductExtendedSchema.Object && type.isList) {
            emptyList<Any?>()
        } else {
            null
        }
    }

// *** TypeDef-related utilities *** //

fun ViaductExtendedSchema.TypeDef.hashForSharding(): Int {
    val h = name.hashCode()
    return if (0 <= h) h else -h
}

fun ViaductExtendedSchema.Object.isEligible(baseTypeMapper: BaseTypeMapper): Boolean {
    if (name == "Query" || name == "Mutation") return true

    // PagedConnection types are generated via Kotlin src codegen (ConnectionTypeGenerator)
    return !isPagedConnection &&
        !cfg.nativeGraphQLTypeToKmName(baseTypeMapper).containsKey(name)
}

// Internal for test generators
val ViaductExtendedSchema.TypeDef.isPagedConnection
    get() =
        this is ViaductExtendedSchema.Object && supers.any { it.name == "PagedConnection" }

/**
 * GraphQL allows implementing interfaces and object-types to add
 * arguments to fields where the parent has none.  This is a feature
 * we can't support in Kotlin, so here we're scanning for this use case.
 */
fun ViaductExtendedSchema.Interface.noArgsAnywhere(fieldName: String): Boolean {
    if (this.field(fieldName)!!.hasArgs) return false

    for (objType in this.possibleObjectTypes) {
        if (objType.field(fieldName)!!.hasArgs) return false
    }
    return true
}

/**
 * True if this type implements the Node interface
 */
val ViaductExtendedSchema.TypeDef.isNode: Boolean
    get() = (name == "Node" && this is ViaductExtendedSchema.Interface) ||
        (this is ViaductExtendedSchema.Record && supers.any { it.isNode })

/**
 * True is this type implements the PagedConnection interface,
 * either directly or indirectly.
 */
val ViaductExtendedSchema.TypeDef.isConnection: Boolean
    get() = (name == "PagedConnection" && this is ViaductExtendedSchema.Interface) ||
        (this is ViaductExtendedSchema.Interface && supers.any { it.isConnection }) ||
        (this is ViaductExtendedSchema.Object && supers.any { it.isConnection })

/** True if this type has a Reflection object generated for it */
val ViaductExtendedSchema.TypeDef.hasReflectedType: Boolean
    // scalar types do not support reflection because they are outside the GRT model
    get() = this !is ViaductExtendedSchema.Scalar

val ViaductExtendedSchema.SourceLocation.tenantModule: String?
    get() = this.sourceName.let {
        cfg.moduleExtractor.find(it)?.groups?.get(1)?.value
    }?.substringBefore("/src/")
