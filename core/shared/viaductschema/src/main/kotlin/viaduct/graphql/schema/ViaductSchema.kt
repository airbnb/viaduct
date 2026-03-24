package viaduct.graphql.schema

import viaduct.utils.collections.BitVector

/**
 * Abstract representation of a GraphQL schema.  The main entry into
 * a schema is the [types] map from type-names to
 * [ViaductSchema.TypeDef] instances.
 *
 * [ViaductSchema] defines a collection of interfaces that are
 * meant to be defined by different "implementations" of
 * [ViaductSchema], e.g., [GJSchema] is an implementation
 * that wraps a graphql-java schema.  To that end, there are no
 * default implementations for any function that returns a type
 * intended to be provided by an implementation.  This class
 * does provided a few default implementations for member
 * functions that return scalars like Boolean and String, and
 * a few static functions for default implementations of member
 * functions that return other types.
 *
 * There is an important invariant maintained by all implementations
 * of [ViaductSchema], which is that they are "closed."  Put
 * differently, object-equality is equality in the schema.  That is
 * within a [ViaductSchema], there's exactly one TypeDef object
 * for each unique type-definition in the GraphQL schema.  And the
 * same is true for field- and enum-value definitions.
 *
 * We have four implementations of [ViaductSchema]:
 *
 *  * `GJSchema` - wraps graphql-java's `GraphQLSchema` — a parsed, validated, and semantically analyzed schema.  Factory: ViaductSchema.fromGraphQLSchema
 *  * `GJSchemaRaw` - wraps graphql-java's `TypeDefinitionRegistry` — a parsed but not semantically validated schema.  Factory: ViaductSchema.fromTypeDefinitionRegistry
 *  * `BSchema` - native `ViaductSchema` implementation with a compact binary serialization format that enables fast loading and reduced memory footprint.  ViaductSchema.fromBinaryFile
 *  * `FilteredSchema` - projects an existing `ViaductSchema` through a filter to create a restricted view.  ViaductSchema.filter
 *
 * The first three of these are extensions on the ViaductSchema
 * companion object that need to be imported.  The fourth
 * is a built-in instance method.
 *
 * If you want to translate [ViaductSchema] object into
 * a graphql-java [TypeDefinitionRegistry], you can call
 * the memeber function [ViaductSchema.toRegistry] (and
 * extension functions).  Among other things this is used
 * to pretty-print schemas by using graphql-java's schema
 * printer.
 *
 * If you want to generate the binary file read by
 * `fromBinaryFile` call [ViaductSchema.toBinaryFile]
 * *also an extension function).
 *
 * [ViaductSchema] allows for types that have no fields, which
 * graphql-java does not.  To increase interoperability with
 * graphql-java and other toolsets, the
 * [ViaductSchema.toRegistry] function inserts a fake field
 * named [ViaductSchema.VIADUCT_IGNORE_SYMBOL] into every object,
 * interface, and input-object type in the schema. In addition,
 * it inserts the fake object-type [ViaductSchema.VIADUCT_IGNORE_SYMBOL]
 * into every schema, and adds this fake type as a fake member to
 * every union of the schema.  This ensures that pretty-printed versions
 * of [ViaductSchema] will be valid GraphQL schemas.  On the
 * other side, both [GJSchema] and [GJSchemaRaw] strip out these fake
 * entries and the fake type.
 *
 * There are a number of places in these classes where we need to
 * represent constant values, in particular the default values of
 * input fields and arguments as well as the values of
 * arguments to applied directives.  [ViaductSchema] uses GraphQL
 * Java's [Value<*>] classes to represent these values.  Keep in
 * mind that these type represent _syntactic_ constants, not
 * semantic ones.  Thus, for example, a [ObjectValue] constant
 * is used to represent both a constant for an GraphQL input
 * type as well as a constant for a JSON scalar.
 */
interface ViaductSchema {
    /** Map of all type definitions in the schema. */
    val types: Map<String, TypeDef>

    /** Map of all directive definitions in the schema. */
    val directives: Map<String, Directive>

    /**
     * Root query type if one defined.  (Unliked GraphQL itself
     * [ViaductSchema] allows for schemas with no root type
     * defined.)  If not null, guaranteed to be a member
     * of [types.values].
     */
    val queryTypeDef: Object?

    /**
     * Root mutation type if one defined.  If not null,
     * guaranteed to be a member of [types.values].
     */
    val mutationTypeDef: Object?

    /**
     * Root subscription type if one defined.  If not null,
     * guaranteed to be a member of [types.values].
     */
    val subscriptionTypeDef: Object?

    /**
     * Returns true if the schema has a custom schema definition, meaning:
     * - Any of the root types (query/mutation/subscription) have non-standard names
     */
    val hasCustomSchema: Boolean
        get() {
            // Check for non-standard root type names
            if (queryTypeDef != null && queryTypeDef?.name != "Query") return true
            if (mutationTypeDef != null && mutationTypeDef?.name != "Mutation") return true
            if (subscriptionTypeDef != null && subscriptionTypeDef?.name != "Subscription") return true
            return false
        }

    /** For testing. */
    object Empty : ViaductSchema {
        override val types = emptyMap<String, TypeDef>()
        override val directives = emptyMap<String, Directive>()
        override val queryTypeDef = null
        override val mutationTypeDef = null
        override val subscriptionTypeDef = null
    }

    /**
     * Produces a projection of [this] according to the
     * logic provided by [filter].  The result is first
     * checked for basic invariants: this check will
     * honor the flags passed in [schemaInvariantOptions].
     */
    fun filter(
        filter: SchemaFilter,
        schemaInvariantOptions: SchemaInvariantOptions = SchemaInvariantOptions.DEFAULT,
    ): ViaductSchema =
        filteredSchema(
            filter,
            this.types.entries,
            directives.entries,
            schemaInvariantOptions,
            queryTypeDef?.name,
            mutationTypeDef?.name,
            subscriptionTypeDef?.name
        )

    /**
     * The directive and arguments of a directive applied to a schema element
     * (e.g., a type-definition, field-definition, etc.).  This is a
     * data class with value-type semantics: [equals] and [hashCode]
     * are based on value equality, not on reference equality.
     *
     * AppliedDirectives are "dense", meaning there is a value for
     * _every_ argument of the directive, including "missing" ones for
     * arguments that either have a default or are nullable.  This is
     * consistent with [ViaductSchema] being for valid schemas, in
     * which case there needs to be values for all arguments.  This
     * dense representation means consumers don't have to chase down
     * directive definitions and apply defauling logic to them.
     *
     * The type parameter [D] allows implementations to preserve type
     * information about the directive definition.
     */
    data class AppliedDirective<out D : ViaductSchema.Directive> private constructor(
        val directive: D,
        val arguments: Map<String, Literal>
    ) {
        val name: String get() = directive.name

        override fun toString() =
            "@$name${
                if (arguments.entries.isNotEmpty()) {
                    "(${
                        arguments.entries.sortedBy { it.key }.joinToString(", ") {
                            "${it.key}: ${it.value}"
                        }
                    })"
                } else {
                    ""
                }
            }"

        companion object {
            /**
             * Factory method to create an AppliedDirective.
             * @param directive The directive definition
             * @param arguments A Map of argument names to values
             * @return An AppliedDirective instance
             */
            fun <D : ViaductSchema.Directive> of(
                directive: D,
                arguments: Map<String, Literal>
            ) = AppliedDirective(directive, arguments)
        }
    }

    /**
     * Not supported yet:  a placeholder for future use cases...
     */
    interface Selection {
        val subselections: Collection<Selection>
        val directives: Collection<ViaductSchema.AppliedDirective<*>>

        interface Conditional : Selection {
            val condition: TypeDef
        }

        interface Field : Selection {
            val fieldName: String
            val arguments: Map<String, Literal>
        }
    }

    enum class TypeDefKind {
        ENUM,
        INPUT,
        INTERFACE,
        SCALAR,
        OBJECT,
        UNION;

        /** True for enum and scalar types. */
        val isSimple
            get() = (this == SCALAR || this == ENUM)

        /** True for object, interface, and union types. */
        val isComposite
            get() = (this == OBJECT || this == INTERFACE || this == UNION)

        /** True for input types. */
        val isInput
            get() = (this == INPUT)

        /** True for output types (object, interface, union, scalar, enum). */
        val isOutput
            get() = (this != INPUT)
    }

    interface Directive : TopLevelDef {
        val containingSchema: ViaductSchema
        override val name: String
        val args: Collection<DirectiveArg>
        val allowedLocations: Set<Location>
        val isRepeatable: Boolean

        override val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>

        override fun describe() = "Directive<$name>[${if (isRepeatable) "repeatable on" else ""} ${allowedLocations.joinToString("| ")}]"

        /**
         * Locations in the GraphQL schema that a directive is allowed
         * to be applied.  Names come from the GraphQL specification.
         */
        enum class Location {
            QUERY,
            MUTATION,
            SUBSCRIPTION,
            FIELD,
            FRAGMENT_DEFINITION,
            FRAGMENT_SPREAD,
            INLINE_FRAGMENT,
            VARIABLE_DEFINITION,
            SCHEMA,
            SCALAR,
            OBJECT,
            FIELD_DEFINITION,
            ARGUMENT_DEFINITION,
            INTERFACE,
            UNION,
            ENUM,
            ENUM_VALUE,
            INPUT_OBJECT,
            INPUT_FIELD_DEFINITION
        }
    }

    data class SourceLocation(
        val sourceName: String
    )

    interface Extension<out D : TypeDef, out M : Def> {
        val def: D
        val members: Collection<M>
        val isBase: Boolean
        val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>
        val sourceLocation: SourceLocation?

        fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

        companion object {
            fun <D : TypeDef, M : Def> of(
                def: D,
                memberFactory: (Extension<D, M>) -> Collection<M>,
                isBase: Boolean,
                appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>,
                sourceLocation: SourceLocation? = null
            ) = object : Extension<D, M> {
                override val def = def
                override val members = memberFactory(this)
                override val isBase = isBase
                override val appliedDirectives = appliedDirectives
                override val sourceLocation = sourceLocation
            }
        }
    }

    interface ExtensionWithSupers<out D : TypeDef, out M : Def> : Extension<D, M> {
        val supers: Collection<Interface>

        companion object {
            fun <D : TypeDef, M : Def> of(
                def: D,
                memberFactory: (Extension<D, M>) -> Collection<M>,
                isBase: Boolean,
                appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>,
                supers: Collection<Interface>,
                sourceLocation: SourceLocation? = null
            ) = object : ExtensionWithSupers<D, M> {
                override val def = def
                override val members = memberFactory(this)
                override val isBase = isBase
                override val appliedDirectives = appliedDirectives
                override val supers = supers
                override val sourceLocation = sourceLocation
            }
        }
    }

    /** Supertype of all type definitions, as well as field
     *  enum-value definitions.  Compare to GraphQLDirectiveContainer
     *  in graphql-java.
     */
    interface Def {
        val name: String
        val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>
        val sourceLocation: SourceLocation?

        fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

        fun describe(): String

        /** Override this in schema implementations that wrap other definitions, e.g. FilteredSchema */
        fun unwrapAll(): Def = this
    }

    /**
     * Base type for top-level definitions that appear in a schema (Directive and TypeDef).
     */
    interface TopLevelDef : Def

    interface TypeDef : TopLevelDef {
        val containingSchema: ViaductSchema
        val kind: TypeDefKind
        val extensions: Collection<Extension<TypeDef, Def>>

        /** True for scalar and enumeration types. */
        val isSimple: Boolean
            get() = kind.isSimple

        /** True for object, interface, and union types. */
        val isComposite: Boolean
            get() = kind.isComposite

        /** True for input types. */
        val isInput: Boolean
            get() = kind.isInput

        /** True for output types (object, interface, union, scalar, enum). */
        val isOutput: Boolean
            get() = kind.isOutput

        /** Returns a _nullable_ type-expr for this def. */
        fun asTypeExpr(): TypeExpr<TypeDef>

        /** Returns the set of Object types possibly subsumed by this
         *  type definition.  It's the empty set for any type other
         *  than Object, Interface, or Union. */
        val possibleObjectTypes: Set<Object>
    }

    interface Scalar : TypeDef {
        override val kind get() = TypeDefKind.SCALAR
        override val extensions: Collection<Extension<Scalar, Nothing>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Scalar $this has no extensions" }
            it.sourceLocation
        }

        override fun describe() = "Scalar<$name>"
    }

    interface EnumValue : Def {
        val containingDef: Enum
        val containingExtension: Extension<ViaductSchema.Enum, ViaductSchema.EnumValue>
        override val sourceLocation get() = containingExtension.sourceLocation

        override fun describe() = "EnumValue<$name>"
    }

    interface Enum : TypeDef {
        override val kind get() = TypeDefKind.ENUM

        val values: Collection<EnumValue>
        override val extensions: Collection<Extension<Enum, EnumValue>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Enum $this has no extensions" }
            it.sourceLocation
        }

        fun value(name: String): EnumValue?

        override fun describe() = "Enum<$name>"
    }

    interface Union : TypeDef {
        override val kind get() = TypeDefKind.UNION
        override val extensions: Collection<Extension<Union, Object>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Union $this has no extensions" }
            it.sourceLocation
        }

        override fun describe() = "Union<$name>"
    }

    interface HasDefaultValue : Def {
        val containingDef: Def
        val type: TypeExpr<TypeDef>
        override val sourceLocation get() = containingDef.sourceLocation

        /**
         * Returns the default value; throws NoSuchElementException if none is explicit in the schema.
         */
        val defaultValue: Literal

        /** Returns true if there's an explicitly defined default. */
        val hasDefault: Boolean

        /** Returns the explicit default value if there is one, or NullLiteral if the field
         *  is a nullable field of a non-Object containing definition.
         *  Throws NoSuchElementException for the rest.
         */
        val effectiveDefaultValue: Literal
            get() =
                when {
                    hasDefault -> defaultValue
                    type.isNullable && (containingDef as? TypeDef)?.isOutput != true -> NULL
                    else -> throw NoSuchElementException("No default value for ${this.describe()}")
                }

        /** Returns true iff [effectiveDefaultValue] would _not_ throw an exception. */
        val hasEffectiveDefault
            get() =
                hasDefault || (type.isNullable && (containingDef as? TypeDef)?.isOutput != true)
    }

    interface Arg : HasDefaultValue

    interface FieldArg : Arg {
        override val containingDef: Field

        override fun describe() = "FieldArg<${containingDef.containingDef.name}.${containingDef.name}.$name:$type>"
    }

    interface DirectiveArg : Arg {
        override val containingDef: Directive

        override fun describe() = "DirectiveArg<${containingDef.name}.$name:$type>"
    }

    /** Represents fields for all of interface, object, and input types. */
    interface Field : HasDefaultValue {
        override val containingDef: Record
        val containingExtension: Extension<Record, Field>
        override val sourceLocation get() = containingExtension.sourceLocation

        /** This is ordered based on the ordering in the schema source text.
         *  Important because code generators may want to order
         *  generated function-signatures in an order that matches
         *  what's in the schema. */
        val args: Collection<FieldArg>

        /** For fields in interfaces and objects, this is true if
         *  this field definition is overriding one from an
         *  implemented interface.  False in all other cases.
         */
        val isOverride: Boolean

        val hasArgs get() = args.isNotEmpty()

        override fun describe() = "Field<$name:$type>"
    }

    /** Supertype for GraphQL interface-, input-, and object-types.
     *  This common interface is useful because various aspects of codegen
     *  work the same for all three types. */
    interface Record : TypeDef {
        val fields: Collection<Field>
        override val extensions: Collection<Extension<Record, Field>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Record $this has no extensions" }
            it.sourceLocation
        }

        fun field(name: String): Field?

        // override with "= super.field(path) as Field" to get more precise typing
        fun field(path: Iterable<String>): Field
    }

    /** Supertype for GraphQL interface- and object-types (output record types).
     *  These types have extensions with supers, unlike Input types. */
    interface OutputRecord : Record {
        override val extensions: Collection<ExtensionWithSupers<OutputRecord, Field>>

        /** The list of interfaces directly implemented by this type. */
        val supers: Collection<Interface>
    }

    interface Interface : OutputRecord {
        override val kind get() = TypeDefKind.INTERFACE
        override val extensions: Collection<ExtensionWithSupers<Interface, Field>>

        override fun describe() = "Interface<$name>"
    }

    interface Object : OutputRecord {
        override val kind get() = TypeDefKind.OBJECT
        override val extensions: Collection<ExtensionWithSupers<Object, Field>>

        /** The list of unions that contain this object type. */
        val unions: Collection<Union>

        override fun describe() = "Object<$name>"
    }

    interface Input : Record {
        override val kind get() = TypeDefKind.INPUT
        override val extensions: Collection<Extension<Input, Field>>

        override fun describe() = "Input<$name>"
    }

    /** A type expression is the type of a GraphQL value.
     *  Type expressions are used to provide static types
     *  to fields and to arguments.
     *
     *  The property `baseTypeDef` contains the base-type
     *  of the expression.
     *
     *  The property `baseTypeNullable` indicates whether or
     *  not that base-type is nullable.
     *
     *  The property `listNullable` is a bit-vector describing
     *  the list-structure of the type (if any).  The size of
     *  this vector indicates the depth of the list-nesting
     *  (size zero means the type is not a list, size one
     *  means a list of the base type, size two means a list
     *  of lists of the base type, and so forth).
     *
     *  For each list depth, the corresponding bit in `listDepth`
     *  indicates whether or not that list is nullable.  (Note
     *  bit zero corresponds to list-depth zero corresponds to
     *  the _outermost_ list-wrapper for the type.)
     *
     *  Examples:
     *
     *     - base= String, baseNullable= true, listNullable.size=0
     *     this would a nullable String.
     *
     *     - base= Int, baseNullable = false, listNullable=0b10
     *     this would be a non-nullable list of nullable lists
     *     of non-nullable integers.  (Why?  The outer-most
     *     non-nullable is list-depth zero, which corresponds
     *     to bit zero (LSB), whose value is zero - which means
     *     non-nullable.  The inner-list is list-depth one, which
     *     corresponds to bit one, whose value is one - which means
     *     nullable.)
     *
     *  The equality (and hashcode) operations reflect the following
     *  assumptions: two type-expressions are equal iff (a) their
     *  type-names are equal (because they are assumed to come from
     *  the same schema), their base-type nullable indicators are
     *  equal, and their listNullable bit vectors are equal.
     *
     *  The type parameter `T` allows implementations to preserve
     *  type information about the baseTypeDef.
     */
    class TypeExpr<out T : TypeDef>(
        val baseTypeDef: T,
        val baseTypeNullable: Boolean = true,
        val listNullable: BitVector = NO_WRAPPERS
    ) {
        /** Scalar or enum type. */
        val isSimple get() = (listNullable.size == 0 && baseTypeDef.isSimple)
        val isList get() = (listNullable.size != 0)
        val isNullable get() = if (isList) listNullable.get(0) else baseTypeNullable

        val listDepth get() = listNullable.size

        /** Strip all list wrappers but maintain both base type and
         *  its nullability. */
        fun unwrapLists(): TypeExpr<T> = TypeExpr(baseTypeDef, baseTypeNullable, NO_WRAPPERS)

        /**
         * Unwrap one level of list depth.
         *
         * @returns null if [this] is not a list, the unwrapped list otherwise
         */
        fun unwrapList(): TypeExpr<T>? =
            if (listNullable.size == 0) {
                null
            } else {
                TypeExpr(baseTypeDef, baseTypeNullable, listNullable.lsr())
            }

        fun nullableAtDepth(depth: Int): Boolean {
            require(depth in 0..listDepth)
            return if (isList && depth < listDepth) listNullable.get(depth) else baseTypeNullable
        }

        override fun equals(other: Any?) =
            other is TypeExpr<*> &&
                baseTypeDef.name == other.baseTypeDef.name &&
                baseTypeNullable == other.baseTypeNullable &&
                listNullable == other.listNullable

        override fun hashCode() = (31 * 31) * baseTypeDef.name.hashCode() + 31 * baseTypeNullable.hashCode() + listNullable.hashCode()

        override fun toString() = "${unparseWrappers()} ${baseTypeDef.name}"

        companion object {
            val NO_WRAPPERS = BitVector(0)
        }
    }

    companion object {
        // Used to indicate that a field or type should be ignored by ViaductSchema
        // but allowed to be used in other intermediate representations like GJSchema, GJSchemaRaw,
        // compilation schema sdl files, etc.
        final const val VIADUCT_IGNORE_SYMBOL = "VIADUCT_IGNORE"

        /**
         * The singleton instance of [NullLiteral].
         */
        @JvmStatic
        val NULL: NullLiteral = NullLiteral()

        /**
         * The singleton instance of [TrueLiteral].
         */
        @JvmStatic
        val TRUE: TrueLiteral = TrueLiteral()

        /**
         * The singleton instance of [FalseLiteral].
         */
        @JvmStatic
        val FALSE: FalseLiteral = FalseLiteral()

        // use in impl class as "override fun field(...): Field = ViaductSchema.field(this, rec, path)"
        inline fun <reified T : Field> field(
            rec: Record,
            path: Iterable<String>
        ): T {
            val pathIter = path.iterator()
            if (!pathIter.hasNext()) throw IllegalArgumentException("Path must have at least one member.")
            var i = 0
            var result: Field? = rec.field(pathIter.next())
            while (true) {
                if (result == null) throw IllegalArgumentException("Missing path segment ($path @ $i).")
                if (!pathIter.hasNext()) break
                val subrec = result.type.baseTypeDef
                if (subrec !is Record) throw IllegalArgumentException("Non-record path segment ($path @ $i).")
                result = subrec.field(pathIter.next())
                i++
            }
            return result!! as T
        }

        // use as "override val isOverride by lazy { ViaductSchema.isOverride(this) }"
        fun isOverride(field: Field): Boolean {
            val containingDef = field.containingDef
            if (containingDef !is OutputRecord) return false
            for (s in containingDef.supers) {
                if (s.field(field.name) != null) return true
            }
            return false
        }
    }

    // =========================================================================
    // Literal types - representations of GraphQL Input Values
    // See: https://spec.graphql.org/October2021/#sec-Input-Values
    // =========================================================================

    /**
     * Represents a GraphQL input value (constant) as used in schema definitions
     * for default values and applied directive arguments.
     *
     * This is a syntactic representation following the GraphQL specification's
     * Input Values grammar. All implementations are immutable and have value
     * equality semantics.
     *
     * The [value] property returns the semantic value in a convenient Kotlin type:
     * - [IntLiteral] → [java.math.BigInteger]
     * - [FloatLiteral] → [java.math.BigDecimal]
     * - [StringLiteral] → [String] (unquoted, unescaped content)
     * - [BooleanLiteral] → [Boolean]
     * - [NullLiteral] → null
     * - [EnumLit] → [String] (the enum literal)
     * - [ListLiteral] → List<Any?> (recursively converted)
     * - [ObjectLiteral] → Map<String, Any?> (recursively converted)
     */
    sealed interface Literal {
        /**
         * Returns the semantic value in a convenient Kotlin type.
         * See class documentation for the mapping of types.
         */
        val value: Any?
    }

    /**
     * Represents a GraphQL null literal.
     *
     * Use [ViaductSchema.NULL] to obtain the singleton instance.
     * This class is not a Kotlin object for Java compatibility.
     */
    class NullLiteral internal constructor() : Literal {
        override val value: Any? = null

        override fun toString() = "null"

        override fun equals(other: Any?) = other is NullLiteral

        override fun hashCode() = 0
    }

    /**
     * Sealed interface for GraphQL boolean literals.
     *
     * Use [ViaductSchema.TRUE] or [ViaductSchema.FALSE] to obtain singleton instances.
     */
    sealed interface BooleanLiteral : Literal {
        override val value: Boolean
    }

    /**
     * Represents a GraphQL `true` literal.
     *
     * Use [ViaductSchema.TRUE] to obtain the singleton instance.
     */
    class TrueLiteral internal constructor() : BooleanLiteral {
        override val value: Boolean = true

        override fun toString() = "true"

        override fun equals(other: Any?) = other is TrueLiteral

        override fun hashCode() = 1
    }

    /**
     * Represents a GraphQL `false` literal.
     *
     * Use [ViaductSchema.FALSE] to obtain the singleton instance.
     */
    class FalseLiteral internal constructor() : BooleanLiteral {
        override val value: Boolean = false

        override fun toString() = "false"

        override fun equals(other: Any?) = other is FalseLiteral

        override fun hashCode() = 0
    }

    /**
     * Represents a GraphQL string literal.
     *
     * The [content] holds the actual string value (unquoted, with escapes interpreted).
     * The [toString] method returns the GraphQL representation (quoted, escaped).
     */
    class StringLiteral private constructor(
        private val content: String
    ) : Literal {
        override val value: String get() = content

        @Volatile
        private var mToString: String? = null

        override fun toString(): String {
            var result = mToString
            if (result == null) {
                result = quoteAndEscape(content)
                mToString = result
            }
            return result
        }

        override fun equals(other: Any?) = other is StringLiteral && content == other.content

        override fun hashCode() = content.hashCode()

        companion object {
            /**
             * Creates a [StringLiteral] from the given string content.
             *
             * @param content The actual string value (not quoted, not escaped)
             */
            @JvmStatic
            fun of(content: String) = StringLiteral(content)

            private fun quoteAndEscape(s: String): String {
                val sb = StringBuilder(s.length + 2)
                sb.append('"')
                for (c in s) {
                    when (c) {
                        '"' -> sb.append("\\\"")
                        '\\' -> sb.append("\\\\")
                        '\b' -> sb.append("\\b")
                        '\u000C' -> sb.append("\\f")
                        '\n' -> sb.append("\\n")
                        '\r' -> sb.append("\\r")
                        '\t' -> sb.append("\\t")
                        else -> {
                            if (c.code < 0x20) {
                                sb.append("\\u")
                                sb.append(String.format("%04x", c.code))
                            } else {
                                sb.append(c)
                            }
                        }
                    }
                }
                sb.append('"')
                return sb.toString()
            }
        }
    }

    /**
     * Represents a GraphQL integer literal.
     *
     * The literal must conform to the GraphQL IntValue grammar:
     * - Optional leading minus sign
     * - No leading zeros (except for the value 0 itself)
     *
     * The [value] property returns the parsed [java.math.BigInteger].
     */
    class IntLiteral private constructor(
        private val literal: String
    ) : Literal {
        @Volatile
        private var mValue: java.math.BigInteger? = null

        override val value: java.math.BigInteger
            get() {
                var result = mValue
                if (result == null) {
                    result = java.math.BigInteger(literal)
                    mValue = result
                }
                return result
            }

        override fun toString() = literal

        override fun equals(other: Any?) = other is IntLiteral && literal == other.literal

        override fun hashCode() = literal.hashCode()

        companion object {
            // GraphQL IntValue: IntegerPart
            // IntegerPart: NegativeSign? 0 | NegativeSign? NonZeroDigit Digit*
            private val INT_PATTERN = Regex("^-?(?:0|[1-9][0-9]*)$")

            /**
             * Creates an [IntLiteral] from a GraphQL integer literal string.
             *
             * @param literal The GraphQL integer literal (no leading zeros except for "0" or "-0")
             * @throws IllegalArgumentException if the literal does not conform to GraphQL IntValue grammar
             */
            @JvmStatic
            fun of(literal: String): IntLiteral {
                require(INT_PATTERN.matches(literal)) {
                    "Invalid GraphQL IntValue literal: '$literal'"
                }
                return IntLiteral(literal)
            }
        }
    }

    /**
     * Represents a GraphQL float literal.
     *
     * The literal must conform to the GraphQL FloatValue grammar:
     * - Integer part with optional minus sign and no leading zeros
     * - Either a fractional part, exponent part, or both
     *
     * The [value] property returns the parsed [java.math.BigDecimal].
     */
    class FloatLiteral private constructor(
        private val literal: String
    ) : Literal {
        @Volatile
        private var mValue: java.math.BigDecimal? = null

        override val value: java.math.BigDecimal
            get() {
                var result = mValue
                if (result == null) {
                    result = java.math.BigDecimal(literal)
                    mValue = result
                }
                return result
            }

        override fun toString() = literal

        override fun equals(other: Any?) = other is FloatLiteral && literal == other.literal

        override fun hashCode() = literal.hashCode()

        companion object {
            // GraphQL FloatValue: IntegerPart FractionalPart | IntegerPart ExponentPart | IntegerPart FractionalPart ExponentPart
            // IntegerPart: NegativeSign? 0 | NegativeSign? NonZeroDigit Digit*
            // FractionalPart: . Digit+
            // ExponentPart: ExponentIndicator Sign? Digit+
            private val FLOAT_PATTERN = Regex("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$")

            /**
             * Creates a [FloatLiteral] from a GraphQL float literal string.
             *
             * @param literal The GraphQL float literal
             * @throws IllegalArgumentException if the literal does not conform to GraphQL FloatValue grammar
             */
            @JvmStatic
            fun of(literal: String): FloatLiteral {
                require(FLOAT_PATTERN.matches(literal)) {
                    "Invalid GraphQL FloatValue literal: '$literal'"
                }
                // Must have either fractional or exponent part (or both) to be a FloatValue
                require(literal.contains('.') || literal.contains('e') || literal.contains('E')) {
                    "GraphQL FloatValue must have fractional or exponent part: '$literal'"
                }
                return FloatLiteral(literal)
            }
        }
    }

    /**
     * Represents a GraphQL enum literal (an input value of enum type).
     *
     * Note: This class is named `EnumLit` (not `EnumLiteral`) to avoid conflict with
     * the [ViaductSchema.EnumValue] interface which represents enum value definitions
     * (members of an enum type).
     *
     * The literal must conform to the GraphQL EnumValue grammar:
     * - Must be a valid Name (starts with letter or underscore, contains letters, digits, underscores)
     * - Must not be `true`, `false`, or `null`
     *
     * The [value] property returns the enum literal as a [String].
     */
    class EnumLit private constructor(
        private val literal: String
    ) : Literal {
        override val value: String get() = literal

        override fun toString() = literal

        override fun equals(other: Any?) = other is EnumLit && literal == other.literal

        override fun hashCode() = literal.hashCode()

        companion object {
            // GraphQL Name: /[_A-Za-z][_0-9A-Za-z]*/
            private val NAME_PATTERN = Regex("^[_A-Za-z][_0-9A-Za-z]*$")
            private val RESERVED = setOf("true", "false", "null")

            /**
             * Creates an [EnumLit] from a GraphQL enum literal string.
             *
             * @param literal The GraphQL enum literal (a valid Name that is not true, false, or null)
             * @throws IllegalArgumentException if the literal does not conform to GraphQL EnumValue grammar
             */
            @JvmStatic
            fun of(literal: String): EnumLit {
                require(NAME_PATTERN.matches(literal)) {
                    "Invalid GraphQL EnumValue literal: '$literal' (must be a valid Name)"
                }
                require(literal !in RESERVED) {
                    "Invalid GraphQL EnumValue literal: '$literal' (cannot be true, false, or null)"
                }
                return EnumLit(literal)
            }
        }
    }

    /**
     * Represents a GraphQL list literal.
     *
     * This class implements [List]<[Literal]> by delegating to the underlying elements.
     *
     * The [value] property returns a List<Any?> with recursively converted element values.
     *
     * **Important**: The caller must provide an immutable list; no defensive copy is made.
     */
    class ListLiteral private constructor(
        private val elements: List<Literal>
    ) : Literal, List<Literal> by elements {
        @Volatile
        private var mValue: List<Any?>? = null

        override val value: List<Any?>
            get() {
                var result = mValue
                if (result == null) {
                    result = elements.map { it.value }
                    mValue = result
                }
                return result
            }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append('[')
            elements.forEachIndexed { index, element ->
                if (index > 0) sb.append(", ")
                sb.append(element.toString())
            }
            sb.append(']')
            return sb.toString()
        }

        override fun equals(other: Any?) = other is ListLiteral && elements == other.elements

        override fun hashCode() = elements.hashCode()

        companion object {
            /**
             * Creates a [ListLiteral] from the given list of values.
             *
             * **Important**: The caller must provide an immutable list; no defensive copy is made.
             *
             * @param elements The list of values (must be immutable)
             */
            @JvmStatic
            fun of(elements: List<Literal>) = ListLiteral(elements)
        }
    }

    /**
     * Represents a GraphQL object (input object) literal.
     *
     * This class implements [Map]<[String], [Literal]> by delegating to the underlying field map.
     *
     * The [value] property returns a Map<String, Any?> with recursively converted entry values.
     *
     * **Important**: The caller must provide an immutable map; no defensive copy is made.
     */
    class ObjectLiteral private constructor(
        private val fieldMap: Map<String, Literal>
    ) : Literal, Map<String, Literal> by fieldMap {
        @Volatile
        private var mValue: Map<String, Any?>? = null

        override val value: Map<String, Any?>
            get() {
                var result = mValue
                if (result == null) {
                    result = fieldMap.mapValues { it.value.value }
                    mValue = result
                }
                return result
            }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append('{')
            fieldMap.entries.forEachIndexed { index, (key, v) ->
                if (index > 0) sb.append(", ")
                sb.append(key)
                sb.append(": ")
                sb.append(v.toString())
            }
            sb.append('}')
            return sb.toString()
        }

        override fun equals(other: Any?) = other is ObjectLiteral && fieldMap == other.fieldMap

        override fun hashCode() = fieldMap.hashCode()

        companion object {
            /**
             * Creates an [ObjectLiteral] from the given map of fields.
             *
             * **Important**: The caller must provide an immutable map; no defensive copy is made.
             *
             * @param fields The map of field names to values (must be immutable)
             */
            @JvmStatic
            fun of(fields: Map<String, Literal>) = ObjectLiteral(fields)
        }
    }
}
