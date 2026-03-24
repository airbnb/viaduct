package viaduct.graphql.schema

/**
 * A unified implementation of [ViaductSchema] that stores optional auxiliary data
 * associated with each schema node via a generic [data] property.
 *
 * This class consolidates what were previously separate implementations (BSchema,
 * GJSchema, GJSchemaRaw, FilteredSchema) into a single class hierarchy. Each
 * "flavor" of schema is distinguished by what it stores in [data]:
 *
 * - Binary format (formerly BSchema): `data` is null for all nodes
 * - GraphQL-Java validated (formerly GJSchema): `data` holds GraphQL* types
 * - GraphQL-Java raw (formerly GJSchemaRaw): `data` holds graphql.language.* types
 * - Filtered (formerly FilteredSchema): `data` holds the unfiltered ViaductSchema.Def
 *
 * Factory functions and type-safe extension properties for accessing [data] are
 * provided in the respective flavor's module.
 *
 * This class is public to support implementations that expose auxiliary data
 * associated with schema nodes. However, consumers should generally work through
 * the [ViaductSchema] interface and flavor-specific extension properties.
 */
internal class SchemaWithData : ViaductSchema {
    private var mDirectives: Map<String, Directive>? = null
    private var mTypes: Map<String, TypeDef>? = null
    private var mQueryTypeDef: Object? = null
    private var mMutationTypeDef: Object? = null
    private var mSubscriptionTypeDef: Object? = null

    override val directives: Map<String, Directive> get() = guardedGet(mDirectives)
    override val types: Map<String, TypeDef> get() = guardedGet(mTypes)
    override val queryTypeDef: Object? get() = guardedGetNullable(mQueryTypeDef, mDirectives)
    override val mutationTypeDef: Object? get() = guardedGetNullable(mMutationTypeDef, mDirectives)
    override val subscriptionTypeDef: Object? get() = guardedGetNullable(mSubscriptionTypeDef, mDirectives)

    internal fun populate(
        directives: Map<String, Directive>,
        types: Map<String, TypeDef>,
        queryTypeDef: Object?,
        mutationTypeDef: Object?,
        subscriptionTypeDef: Object?,
    ) {
        check(mDirectives == null) { "Schema has already been populated; populate() can only be called once" }
        mDirectives = directives
        mTypes = types
        mQueryTypeDef = queryTypeDef
        mMutationTypeDef = mutationTypeDef
        mSubscriptionTypeDef = subscriptionTypeDef
    }

    override fun toString() = types.toString()

    //
    // [Def] related classes
    //

    sealed class Def protected constructor() : ViaductSchema.Def {
        abstract val data: Any?

        override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

        override fun toString() = describe()

        /**
         * Unwrap all layers of filtering.
         * If [data] contains a [ViaductSchema.Def], recursively unwrap it.
         * Otherwise return this.
         */
        override fun unwrapAll(): ViaductSchema.Def = (data as? ViaductSchema.Def)?.unwrapAll() ?: this
    }

    /**
     * Base class for top-level definitions that appear in a schema (Directive and TypeDef).
     */
    sealed class TopLevelDef protected constructor() : Def(), ViaductSchema.TopLevelDef

    //
    // "Contained" things:
    // [Arg], [Field] and [EnumValue] and related classes
    //

    sealed class HasDefaultValue protected constructor() : Def(), ViaductSchema.HasDefaultValue {
        // Leave abstract so we can narrow the type
        abstract override val containingDef: Def

        protected abstract val mDefaultValue: ViaductSchema.Literal?

        override val defaultValue: ViaductSchema.Literal
            get() =
                if (hasDefault) {
                    mDefaultValue!!
                } else {
                    throw NoSuchElementException("No default value for ${this.describe()}")
                }
    }

    sealed class Arg protected constructor() : HasDefaultValue(), ViaductSchema.Arg

    class DirectiveArg internal constructor(
        override val containingDef: Directive,
        override val name: String,
        override val type: ViaductSchema.TypeExpr<TypeDef>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
        override val hasDefault: Boolean,
        override val mDefaultValue: ViaductSchema.Literal?,
        override val data: Any? = null,
    ) : Arg(), ViaductSchema.DirectiveArg

    class FieldArg internal constructor(
        override val containingDef: Field,
        override val name: String,
        override val type: ViaductSchema.TypeExpr<TypeDef>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
        override val hasDefault: Boolean,
        override val mDefaultValue: ViaductSchema.Literal?,
        override val data: Any? = null,
    ) : Arg(), ViaductSchema.FieldArg

    class EnumValue internal constructor(
        override val containingExtension: ViaductSchema.Extension<Enum, EnumValue>,
        override val name: String,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
        override val data: Any? = null,
    ) : Def(), ViaductSchema.EnumValue {
        override val containingDef: Enum get() = containingExtension.def
    }

    class Field internal constructor(
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        override val name: String,
        override val type: ViaductSchema.TypeExpr<TypeDef>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
        override val hasDefault: Boolean,
        override val mDefaultValue: ViaductSchema.Literal?,
        override val data: Any? = null,
        argsFactory: (Field) -> List<FieldArg> = { emptyList() },
    ) : HasDefaultValue(), ViaductSchema.Field {
        /** Secondary constructor for fields without arguments (e.g., input fields). */
        internal constructor(
            containingExtension: ViaductSchema.Extension<Record, Field>,
            name: String,
            type: ViaductSchema.TypeExpr<TypeDef>,
            appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
            hasDefault: Boolean,
            defaultValue: ViaductSchema.Literal?,
            data: Any? = null,
        ) : this(containingExtension, name, type, appliedDirectives, hasDefault, defaultValue, data, { emptyList() })

        override val args: List<FieldArg> = argsFactory(this)

        override val isOverride: Boolean by lazy { ViaductSchema.isOverride(this) }

        override val containingDef: Record get() = containingExtension.def
    }

    //
    // [Directive] concrete class
    //

    class Directive internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val data: Any? = null,
    ) : TopLevelDef(), ViaductSchema.Directive {
        private var mSourceLocation: ViaductSchema.SourceLocation? = null
        private var mIsRepeatable: Boolean? = null
        private var mAllowedLocations: Set<ViaductSchema.Directive.Location>? = null
        private var mArgs: List<DirectiveArg>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = guardedGetNullable(mSourceLocation, mArgs)
        override val isRepeatable: Boolean get() = guardedGet(mIsRepeatable)
        override val allowedLocations: Set<ViaductSchema.Directive.Location> get() = guardedGet(mAllowedLocations)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = emptyList()
        override val args: List<DirectiveArg> get() = guardedGet(mArgs)

        internal fun populate(
            isRepeatable: Boolean,
            allowedLocations: Set<ViaductSchema.Directive.Location>,
            sourceLocation: ViaductSchema.SourceLocation?,
            args: List<DirectiveArg>
        ) {
            check(mArgs == null) { "Directive $name has already been populated; populate() can only be called once" }
            mIsRepeatable = isRepeatable
            mAllowedLocations = allowedLocations
            mSourceLocation = sourceLocation
            mArgs = args
        }
    }

    //
    // [TypeDef] related classes
    //

    sealed class TypeDef protected constructor() : TopLevelDef(), ViaductSchema.TypeDef {
        abstract override val containingSchema: SchemaWithData

        override fun asTypeExpr(): ViaductSchema.TypeExpr<TypeDef> = ViaductSchema.TypeExpr(this)

        open override val possibleObjectTypes: Set<Object> get() = emptySet()
    }

    //
    // Non-[Record] [TypeDef] concrete classes
    //

    class Scalar internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val data: Any? = null,
    ) : TypeDef(), ViaductSchema.Scalar {
        private var mExtensions: List<ViaductSchema.Extension<Scalar, Nothing>>? = null

        override val extensions: List<ViaductSchema.Extension<Scalar, Nothing>> get() = guardedGet(mExtensions)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = extensions.flatMap { it.appliedDirectives }
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        internal fun populate(extensions: List<ViaductSchema.Extension<Scalar, Nothing>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
        }
    }

    class Enum internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val data: Any? = null,
    ) : TypeDef(), ViaductSchema.Enum {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.Extension<Enum, EnumValue>>? = null
        private var mValues: List<EnumValue>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Enum, EnumValue>> get() = guardedGet(mExtensions)
        override val values: List<EnumValue> get() = guardedGet(mValues)

        internal fun populate(extensions: List<ViaductSchema.Extension<Enum, EnumValue>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mValues = extensions.flatMap { it.members }
        }

        override fun value(name: String) = values.find { name == it.name }
    }

    class Union internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val data: Any? = null,
    ) : TypeDef(), ViaductSchema.Union {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.Extension<Union, Object>>? = null
        private var mPossibleObjectTypes: Set<Object>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Union, Object>> get() = guardedGet(mExtensions)
        override val possibleObjectTypes: Set<Object> get() = guardedGet(mPossibleObjectTypes)

        internal fun populate(extensions: List<ViaductSchema.Extension<Union, Object>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            // Validate that all members are actually Object instances
            // Cast to Collection<*> bypasses type erasure so runtime check works
            for (ext in extensions) {
                @Suppress("UNCHECKED_CAST", "USELESS_CAST") // Cast bypasses type erasure for runtime check
                for (member in ext.members as Collection<*>) {
                    @Suppress("USELESS_IS_CHECK") // Defensive check for corrupt binary data
                    if (member !is Object) {
                        val typeName = (member as? ViaductSchema.TypeDef)?.name ?: member.toString()
                        throw InvalidSchemaException(
                            "Union $name contains member $typeName which is not an Object (got ${member?.javaClass?.simpleName})"
                        )
                    }
                }
            }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mPossibleObjectTypes = extensions.flatMap { it.members }.toSet()
        }
    }

    //
    // [Record] and its concrete classes
    //

    sealed class Record protected constructor() : TypeDef(), ViaductSchema.Record {
        abstract override val fields: List<Field>

        override fun field(name: String) = fields.find { name == it.name }

        override fun field(path: Iterable<String>): Field = ViaductSchema.field(this, path)
    }

    sealed class OutputRecord protected constructor() : Record(), ViaductSchema.OutputRecord {
        abstract override val extensions: List<ViaductSchema.ExtensionWithSupers<OutputRecord, Field>>
        abstract override val supers: List<Interface>
    }

    class Interface internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val data: Any? = null,
    ) : OutputRecord(), ViaductSchema.Interface {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>>? = null
        private var mFields: List<Field>? = null
        private var mSupers: List<Interface>? = null
        private var mPossibleObjectTypes: Set<Object>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val supers: List<Interface> get() = guardedGet(mSupers)
        override val possibleObjectTypes: Set<Object> get() = guardedGet(mPossibleObjectTypes)

        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>>,
            possibleObjectTypes: Set<Object>
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            // Validate possibleObjectTypes contains actual Object instances
            // Cast to Set<*> bypasses type erasure so runtime check works
            @Suppress("UNCHECKED_CAST", "USELESS_CAST") // Cast bypasses type erasure for runtime check
            for (objType in possibleObjectTypes as Set<*>) {
                @Suppress("USELESS_IS_CHECK") // Defensive check for corrupt binary data
                if (objType !is Object) {
                    val typeName = (objType as? ViaductSchema.TypeDef)?.name ?: objType.toString()
                    throw InvalidSchemaException(
                        "Interface $name possibleObjectTypes contains $typeName which is not an Object (got ${objType?.javaClass?.simpleName})"
                    )
                }
            }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mFields = extensions.flatMap { it.members }
            @Suppress("UNCHECKED_CAST")
            mSupers = extensions.flatMap { it.supers as Collection<Interface> }
            mPossibleObjectTypes = possibleObjectTypes
        }
    }

    class Input internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val data: Any? = null,
    ) : Record(), ViaductSchema.Input {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.Extension<Input, Field>>? = null
        private var mFields: List<Field>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Input, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)

        internal fun populate(extensions: List<ViaductSchema.Extension<Input, Field>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mFields = extensions.flatMap { it.members }
        }
    }

    class Object internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val data: Any? = null,
    ) : OutputRecord(), ViaductSchema.Object {
        override val possibleObjectTypes = setOf(this)

        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>>? = null
        private var mFields: List<Field>? = null
        private var mSupers: List<Interface>? = null
        private var mUnions: List<Union>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val supers: List<Interface> get() = guardedGet(mSupers)
        override val unions: List<Union> get() = guardedGet(mUnions)

        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>>,
            unions: List<Union>
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            // Validate that all supers are actually Interface instances
            // Cast to Collection<*> bypasses type erasure so runtime check works
            for (ext in extensions) {
                @Suppress("UNCHECKED_CAST", "USELESS_CAST") // Cast bypasses type erasure for runtime check
                for (superType in ext.supers as Collection<*>) {
                    @Suppress("USELESS_IS_CHECK") // Defensive check for corrupt binary data
                    if (superType !is Interface) {
                        val typeName = (superType as? ViaductSchema.TypeDef)?.name ?: superType.toString()
                        throw InvalidSchemaException(
                            "Object $name implements $typeName which is not an Interface (got ${superType?.javaClass?.simpleName})"
                        )
                    }
                }
            }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mFields = extensions.flatMap { it.members }
            @Suppress("UNCHECKED_CAST")
            mSupers = extensions.flatMap { it.supers as Collection<Interface> }
            mUnions = unions
        }
    }
}

// Helper functions (private to the file)
@Suppress("NOTHING_TO_INLINE")
private inline fun <T> SchemaWithData.guardedGet(v: T?): T = checkNotNull(v) { "Schema has not been populated; call populate() first" }

@Suppress("NOTHING_TO_INLINE")
private inline fun <T> SchemaWithData.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "Schema has not been populated; call populate() first" }
    return v
}

@Suppress("NOTHING_TO_INLINE")
private inline fun <T> SchemaWithData.TopLevelDef.guardedGet(v: T?): T = checkNotNull(v) { "${this.name} has not been populated; call populate() first" }

@Suppress("NOTHING_TO_INLINE")
private inline fun <T> SchemaWithData.TopLevelDef.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "${this.name} has not been populated; call populate() first" }
    return v
}
