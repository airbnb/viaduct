package viaduct.graphql.schema.builder

import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector
import viaduct.utils.collections.HMap

/**
 * Base class for definitions and extensions added to a
 * [ViaductSchemaBuilder].
 */
sealed class DefinitionBuilder(
    val name: String,
) {
    internal val state = BuilderElementState(this)

    internal fun claim(owner: Any) = state.claim(owner)
}

/**
 * An unresolved GraphQL type expression.
 *
 * The named base type is resolved when its containing
 * [ViaductSchemaBuilder] is built. Instances are immutable and may be reused.
 */
class TypeExprBuilder private constructor(
    val baseTypeName: String,
    val baseTypeNullable: Boolean,
    internal val listNullabilities: List<Boolean>,
) {
    constructor(
        baseTypeName: String,
        nullable: Boolean = true,
    ) : this(baseTypeName, nullable, emptyList())

    /** Wraps this expression in a list with the given nullability. */
    fun list(nullable: Boolean = true): TypeExprBuilder =
        TypeExprBuilder(
            baseTypeName,
            baseTypeNullable,
            listOf(nullable) + listNullabilities,
        )

    internal fun resolve(types: Map<String, SchemaWithData.TypeDef>): ViaductSchema.TypeExpr<SchemaWithData.TypeDef> {
        val baseType = requireNotNull(types[baseTypeName]) {
            "Type '$baseTypeName' is not defined"
        }
        val listNullable = BitVector(listNullabilities.size)
        listNullabilities.forEachIndexed { index, nullable ->
            if (nullable) {
                listNullable.set(index)
            }
        }
        return ViaductSchema.TypeExpr(baseType, baseTypeNullable, listNullable)
    }
}

/** An unresolved application of a directive. */
class AppliedDirectiveBuilder(
    val name: String,
) {
    private val state = BuilderOwnership(this)
    internal val arguments = linkedMapOf<String, ViaductSchema.Literal>()

    /**
     * Adds an explicitly supplied directive argument.
     *
     * Omitted arguments are filled from the directive definition when the
     * schema is built.
     */
    fun addArgument(
        name: String,
        value: ViaductSchema.Literal,
    ): AppliedDirectiveBuilder =
        apply {
            arguments[name] = value
        }

    internal fun claim(owner: Any) = state.claim(owner)
}

internal class BuilderElementState(
    private val builder: Any,
) {
    private val ownership = BuilderOwnership(builder)
    private val holderBuilder = HMap.Builder()
    private var baseHolder = HMap.singleton(null)
    private var hasHolderValues = false

    var description: String? = null
    var sourceLocation: ViaductSchema.SourceLocation? = null
    var defaultValue: ViaductSchema.Literal? = null
    val appliedDirectives = mutableListOf<AppliedDirectiveBuilder>()

    fun claim(owner: Any) = ownership.claim(owner)

    fun addAppliedDirective(directive: AppliedDirectiveBuilder) {
        directive.claim(builder)
        appliedDirectives.add(directive)
    }

    fun <T : Any?> put(
        key: HMap.Key<T>,
        value: T,
    ) {
        holderBuilder.put(key, value)
        hasHolderValues = true
    }

    fun copyHolder(holder: HMap) {
        check(!hasHolderValues) {
            "Cannot copy a holder after values have been added to ${builder.describeBuilder()}"
        }
        baseHolder = holder
    }

    fun buildHolder(): HMap {
        if (!hasHolderValues) {
            return baseHolder
        }
        val values = holderBuilder.build()
        val fallback = baseHolder
        return object : HMap {
            override fun contains(key: HMap.Key<*>): Boolean = key in values || key in fallback

            override fun <T> get(key: HMap.Key<T>): T =
                try {
                    values[key]
                } catch (_: NoSuchElementException) {
                    fallback[key]
                }
        }
    }
}

internal class BuilderOwnership(
    private val builder: Any,
) {
    private var owner: Any? = null

    fun claim(newOwner: Any) {
        check(owner == null) {
            "${builder.describeBuilder()} has already been added to ${owner!!.describeBuilder()}"
        }
        owner = newOwner
    }
}

private fun Any.describeBuilder(): String {
    val builderName =
        when (this) {
            is DefinitionBuilder -> name
            is AppliedDirectiveBuilder -> name
            is ArgumentBuilder -> name
            is EnumValueBuilder -> name
            is OutputFieldBuilder -> name
            is InputFieldBuilder -> name
            else -> null
        }
    val typeName = this::class.simpleName ?: this::class.java.name
    return if (builderName == null) typeName else "$typeName('$builderName')"
}
