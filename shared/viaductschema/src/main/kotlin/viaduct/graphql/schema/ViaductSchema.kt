package viaduct.graphql.schema

import viaduct.utils.collections.BitVector

/** See KDoc for [ViaductSchema] for background. */
interface ViaductSchema {
    val types: Map<String, TypeDef>
    val directives: Map<String, Directive>

    val queryTypeDef: Object?
    val mutationTypeDef: Object?
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

    fun filter(
        filter: SchemaFilter,
        schemaInvariantOptions: SchemaInvariantOptions = SchemaInvariantOptions.DEFAULT,
    ) = FilteredSchema(
        filter,
        this.types.entries,
        directives.entries,
        schemaInvariantOptions,
        queryTypeDef?.name,
        mutationTypeDef?.name,
        subscriptionTypeDef?.name
    )

    // Everything below this line (inside the BridgeSchema definition) are
    // static interface and class definitions

    /** The name and arguments of a directive applied to a schema element
     *  (e.g., a type-definition, field-definition, etc.).  Implementations
     *  of this type must implement "value type" semantics, meaning [equals]
     *  and [hashCode] are based on value equality, not on reference equality.
     */
    interface AppliedDirective {
        val name: String
        val arguments: Map<String, Any?>

        companion object {
            /**
             * This function is used to create an Anonymous Object of the AppliedDirective interface
             * @param name The value to be put for the name of the AppliedDirective
             * @param arguments A Map of String, Any to be put as arguments
             *
             * @return an Anonymous Object of the AppliedDirective instantiated with the parameters.
             */
            fun of(
                name: String,
                arguments: Map<String, Any?>
            ) = object : AppliedDirective {
                override val name = name
                override val arguments = arguments

                override fun equals(other: Any?): Boolean {
                    if (other === this) {
                        return true
                    }
                    if (other == null || other !is AppliedDirective) {
                        return false
                    }
                    if (name != other.name) {
                        return false
                    }
                    return arguments == other.arguments
                }

                override fun hashCode(): Int = name.hashCode() + 31 * arguments.hashCode()

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
            }
        }
    }

    interface Selection {
        val subselections: Collection<Selection>
        val directives: Collection<ViaductSchema.AppliedDirective>

        interface Conditional : Selection {
            val condition: TypeDef
        }

        interface Field : Selection {
            val fieldName: String
            val arguments: Map<String, Any?>
        }
    }

    enum class TypeDefKind {
        ENUM,
        INPUT,
        INTERFACE,
        SCALAR,
        OBJECT,
        UNION;

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

    interface Directive : HasArgs {
        override val name: String
        override val args: Collection<DirectiveArg>
        val allowedLocations: Set<Location>
        val isRepeatable: Boolean

        override val appliedDirectives: Collection<ViaductSchema.AppliedDirective>

        override fun describe() = "Directive<$name>[${if (isRepeatable) "repeatable on" else ""} ${allowedLocations.joinToString("| ")}]"

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
        val appliedDirectives: Collection<ViaductSchema.AppliedDirective>
        val sourceLocation: SourceLocation?

        fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

        companion object {
            fun <D : TypeDef, M : Def> of(
                def: D,
                memberFactory: (Extension<D, M>) -> Collection<M>,
                isBase: Boolean,
                appliedDirectives: Collection<ViaductSchema.AppliedDirective>,
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
                appliedDirectives: Collection<ViaductSchema.AppliedDirective>,
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

    interface HasExtensions<D : TypeDef, M : Def> : TypeDef {
        val extensions: Collection<Extension<D, M>>
    }

    interface HasExtensionsWithSupers<D : Record, M : Field> :
        Record,
        HasExtensions<D, M> {
        override val extensions: Collection<ExtensionWithSupers<D, M>>
    }

    /** Supertype of all type definitions, as well as field
     *  enum-value definitions.  Compare to GraphQLDirectiveContainer
     *  in graphql-java.
     */
    interface Def {
        val name: String
        val appliedDirectives: Collection<ViaductSchema.AppliedDirective>
        val sourceLocation: SourceLocation?

        fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

        fun describe(): String

        /** Override this in schema implementations that wrap other definitions, e.g. FilteredSchema */
        fun unwrapAll(): Def = this
    }

    interface TypeDef : Def {
        val kind: TypeDefKind

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
        fun asTypeExpr(): TypeExpr

        /** Returns the set of Object types possibly subsumed by this
         *  type definition.  It's the empty set for any type other
         *  than Object, Interface, or Union. */
        val possibleObjectTypes: Set<Object>
    }

    interface Scalar : TypeDef {
        override val kind get() = TypeDefKind.SCALAR

        override fun describe() = "Scalar<$name>"
    }

    interface EnumValue : Def {
        val containingDef: Enum
        val containingExtension: Extension<ViaductSchema.Enum, ViaductSchema.EnumValue>
        override val sourceLocation get() = containingExtension.sourceLocation

        override fun describe() = "EnumValue<$name>"
    }

    interface Enum : HasExtensions<Enum, EnumValue> {
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

    interface Union :
        TypeDef,
        HasExtensions<Union, Object> {
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
        val type: TypeExpr
        override val sourceLocation get() = containingDef.sourceLocation

        /** Returns the default value; throws NoSuchElementException if none is explicit in the schema. */
        val defaultValue: Any?

        /** Returns true if there's an explicitly defined default. */
        val hasDefault: Boolean

        /** Returns the explicit default value if there is one, or null if the field
         *  is a nullable field of a non-Object containing definition.
         *  Throws NoSuchElementException for the rest.
         */
        val effectiveDefaultValue
            get() =
                when {
                    hasDefault -> defaultValue
                    type.isNullable && (containingDef as? TypeDef)?.isOutput != true -> null
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

    interface HasArgs : Def {
        val args: Collection<Arg>
    }

    /** Represents fields for all of interface, object, and input types. */
    interface Field :
        HasDefaultValue,
        HasArgs {
        override val containingDef: Record
        val containingExtension: Extension<Record, Field>
        override val sourceLocation get() = containingExtension.sourceLocation

        /** This is ordered based on the ordering in the schema source text.
         *  Important because code generators may want to order
         *  generated function-signatures in an order that matches
         *  what's in the schema. */
        override val args: Collection<FieldArg>

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
        val extensions: Collection<Extension<Record, Field>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Record $this has no extensions" }
            it.sourceLocation
        }

        fun field(name: String): Field?

        // override with "= super.field(path) as Field" to get more precise typing
        fun field(path: Iterable<String>): Field

        /** For object and interface types, the list of interfaces directly
         *  implemented by the type.  Empty for InputTypes. */
        val supers: Collection<Interface>

        /** For object types, the list of unions that contain it (empty for
         *  other types). */
        val unions: Collection<Union>
    }

    interface Interface : HasExtensionsWithSupers<Interface, Field> {
        override val kind get() = TypeDefKind.INTERFACE
        override val extensions: Collection<ExtensionWithSupers<Interface, Field>>

        override fun describe() = "Interface<$name>"
    }

    interface Object : HasExtensionsWithSupers<Object, Field> {
        override val kind get() = TypeDefKind.OBJECT
        override val extensions: Collection<ExtensionWithSupers<Object, Field>>

        override fun describe() = "Object<$name>"
    }

    interface Input :
        Record,
        HasExtensions<Input, Field> {
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
     */
    abstract class TypeExpr {
        // This class overrides equals and hashCode as well as toString -
        // that's too many things to make it an interface as we did all
        // the other types...

        abstract val baseTypeNullable: Boolean
        abstract val baseTypeDef: TypeDef
        abstract val listNullable: BitVector

        /** Scalar or enum type. */
        val isSimple get() = (listNullable.size == 0 && baseTypeDef.isSimple)
        val isList get() = (listNullable.size != 0)
        val isNullable get() = if (isList) listNullable.get(0) else baseTypeNullable

        val listDepth get() = listNullable.size

        /** Strip all list wrappers but maintain both base type and
         *  its nullability. */
        abstract fun unwrapLists(): TypeExpr

        /**
         * Unwrap one level of list depth.
         *
         * @returns null if [this] is not a list, the unwrapped list otherwise
         */
        abstract fun unwrapList(): TypeExpr?

        fun nullableAtDepth(depth: Int): Boolean {
            require(depth in 0..listDepth)
            return if (isList && depth < listDepth) listNullable.get(depth) else baseTypeNullable
        }

        override fun equals(other: Any?) =
            other is TypeExpr &&
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

        // use in impl class as "override fun field(...): Field = BridgeSchema.field(this, rec, path)"
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

        // use as "override val isOverride by lazy { BridgeSchema.isOverride(this) }"
        fun isOverride(field: Field): Boolean {
            for (s in field.containingDef.supers) {
                if (s.field(field.name) != null) return true
            }
            return false
        }
    }
}
