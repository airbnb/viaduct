package viaduct.graphql.schema

import viaduct.utils.collections.BitVector

/** See KDoc for [ViaductSchema] for background. */
interface ViaductExtendedSchema : ViaductSchema {
    override val types: Map<String, TypeDef>
    override val directives: Map<String, Directive>

    override val queryTypeDef: Object?
    override val mutationTypeDef: Object?
    override val subscriptionTypeDef: Object?

    /** For testing. */
    object Empty : ViaductExtendedSchema {
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

    enum class TypeDefKind {
        ENUM,
        INPUT,
        INTERFACE,
        SCALAR,
        OBJECT,
        UNION;

        val isSimple
            get() = (this == SCALAR || this == ENUM)
    }

    interface Directive : ViaductSchema.Directive, HasArgs {
        override val args: Collection<DirectiveArg>
    }

    data class SourceLocation(val sourceName: String)

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

    interface HasExtensionsWithSupers<D : Record, M : Field> : CompositeOutput, Record, HasExtensions<D, M> {
        override val extensions: Collection<ExtensionWithSupers<D, M>>
    }

    /** Supertype of all type definitions, as well as field
     *  enum-value definitions.  Compare to GraphQLDirectiveContainer
     *  in graphql-java.
     */
    interface Def : ViaductSchema.Def {
        override val appliedDirectives: Collection<ViaductSchema.AppliedDirective>

        val sourceLocation: SourceLocation?

        /** Override this in schema implementations that wrap other definitions, e.g. FilteredSchema */
        fun unwrapAll(): Def = this
    }

    interface TypeDef : ViaductSchema.TypeDef, Def {
        val kind: TypeDefKind

        /** True for scalar and enumeration types. */
        override val isSimple: Boolean
            get() = kind.isSimple

        /** Returns a _nullable_ type-expr for this def. */
        override fun asTypeExpr(): TypeExpr

        /** Returns the set of Object types possibly subsumed by this
         *  type definition.  It's the empty set for any type other
         *  than Object, Interface, or Union. */
        override val possibleObjectTypes: Set<Object>
    }

    interface Scalar : ViaductSchema.Scalar, TypeDef {
        override val kind get() = TypeDefKind.SCALAR
    }

    interface EnumValue : ViaductSchema.EnumValue, Def {
        override val containingDef: Enum
        val containingExtension: Extension<ViaductExtendedSchema.Enum, ViaductExtendedSchema.EnumValue>
        override val sourceLocation get() = containingExtension.sourceLocation
    }

    interface Enum : ViaductSchema.Enum, HasExtensions<Enum, EnumValue> {
        override val kind get() = TypeDefKind.ENUM

        override val values: Collection<EnumValue>
        override val extensions: Collection<Extension<Enum, EnumValue>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Enum $this has no extensions" }
            it.sourceLocation
        }

        override fun value(name: String): EnumValue?
    }

    /** Tagging interface for Object, Interface, and Union, i.e.,
     *  anything that can be a supertype of an object-value type. */
    interface CompositeOutput : ViaductSchema.CompositeOutput, TypeDef

    interface Union : ViaductSchema.Union, CompositeOutput, HasExtensions<Union, Object> {
        override val kind get() = TypeDefKind.UNION
        override val extensions: Collection<Extension<Union, Object>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Union $this has no extensions" }
            it.sourceLocation
        }
    }

    interface HasDefaultValue : ViaductSchema.HasDefaultValue, Def {
        override val containingDef: Def
        override val type: TypeExpr
        override val sourceLocation get() = containingDef.sourceLocation

        /** Returns the explicit default value if there is one, or null if the field
         *  is a nullable field of a non-Object containing definition.
         *  Throws NoSuchElementException for the rest.
         */
        override val effectiveDefaultValue
            get() =
                when {
                    hasDefault -> defaultValue
                    type.isNullable && this.containingDef !is CompositeOutput -> null
                    else -> throw NoSuchElementException("No default value for ${this.describe()}")
                }

        /** Returns true iff [effectiveDefaultValue] would _not_ throw an exception. */
        override val hasEffectiveDefault
            get() =
                hasDefault || (type.isNullable && this.containingDef !is CompositeOutput)
    }

    interface Arg : ViaductSchema.Arg, HasDefaultValue

    interface FieldArg : ViaductSchema.FieldArg, Arg {
        override val containingDef: Field
    }

    interface DirectiveArg : ViaductSchema.DirectiveArg, Arg {
        override val containingDef: Directive
    }

    interface HasArgs : ViaductSchema.HasArgs, Def {
        override val args: Collection<Arg>
    }

    /** Represents fields for all of interface, object, and input types. */
    interface Field : ViaductSchema.Field, HasDefaultValue, HasArgs {
        override val containingDef: Record
        val containingExtension: Extension<Record, Field>
        override val sourceLocation get() = containingExtension.sourceLocation

        /** This is ordered based on the ordering in the schema source text.
         *  Important because code generators may want to order
         *  generated function-signatures in an order that matches
         *  what's in the schema. */
        override val args: Collection<FieldArg>

        val hasArgs get() = args.isNotEmpty()
    }

    /** Supertype for GraphQL interface-, input-, and object-types.
     *  This common interface is useful because various aspects of codegen
     *  work the same for all three types. */
    interface Record : ViaductSchema.Record, TypeDef {
        override val fields: Collection<Field>
        val extensions: Collection<Extension<Record, Field>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Record $this has no extensions" }
            it.sourceLocation
        }

        override fun field(name: String): Field?

        // override with "= super.field(path) as Field" to get more precise typing
        override fun field(path: Iterable<String>): Field

        /** For object and interface types, the list of interfaces directly
         *  implemented by the type.  Empty for InputTypes. */
        val supers: Collection<Interface>

        /** For object types, the list of unions that contain it (empty for
         *  other types). */
        val unions: Collection<Union>
    }

    interface Interface : ViaductSchema.Interface, HasExtensionsWithSupers<Interface, Field> {
        override val kind get() = TypeDefKind.INTERFACE
        override val extensions: Collection<ExtensionWithSupers<Interface, Field>>
    }

    interface Object : ViaductSchema.Object, HasExtensionsWithSupers<Object, Field> {
        override val kind get() = TypeDefKind.OBJECT
        override val extensions: Collection<ExtensionWithSupers<Object, Field>>
    }

    interface Input : ViaductSchema.Input, Record, HasExtensions<Input, Field> {
        override val kind get() = TypeDefKind.INPUT
        override val extensions: Collection<Extension<Input, Field>>
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
    abstract class TypeExpr : ViaductSchema.TypeExpr {
        // This class overrides equals and hashCode as well as toString -
        // that's too many things to make it an interface as we did all
        // the other types...

        abstract override val baseTypeNullable: Boolean
        abstract override val baseTypeDef: TypeDef
        abstract val listNullable: BitVector

        /** Scalar or enum type. */
        override val isSimple get() = (listNullable.size() == 0 && baseTypeDef.isSimple)
        override val isList get() = (listNullable.size() != 0)
        override val isNullable get() = if (isList) listNullable.get(0) else baseTypeNullable

        override val listDepth get() = listNullable.size()

        /** Strip all list wrappers but maintain both base type and
         *  its nullability. */
        abstract override fun unwrapLists(): TypeExpr

        override fun nullableAtDepth(depth: Int): Boolean {
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
