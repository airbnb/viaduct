package viaduct.graphql.schema

import viaduct.utils.collections.HMap

/**
 * A unified implementation of [ViaductSchema] that stores optional auxiliary
 * values associated with each schema node in a [HMap].
 *
 * This class consolidates what were previously separate implementations (BSchema,
 * GJSchema, GJSchemaRaw, FilteredSchema) into a single class hierarchy. Each
 * "flavor" of schema is distinguished by what it stores in [ViaductSchema.Def.holder]:
 *
 * - Binary format (formerly BSchema): the default value is null for all nodes
 * - GraphQL-Java validated (formerly GJSchema): a GraphQL* type under a private key
 * - GraphQL-Java raw (formerly GJSchemaRaw): a graphql.language.* type under a private key
 * - Filtered (formerly FilteredSchema): the unfiltered ViaductSchema.Def under a private key
 *
 * Factory functions and type-safe extension properties for accessing holder
 * values are provided in the respective flavor's module.
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

    sealed interface Def : ViaductSchema.Def {
        override val description: String?

        override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

        /**
         * Unwrap all layers of filtering.
         * If this is a filtered definition, recursively unwrap its original definition.
         * Otherwise return this.
         */
        override fun unwrapAll(): ViaductSchema.Def = unfilteredDefOrNull()?.unwrapAll() ?: this
    }

    sealed class DefBase protected constructor() : Def {
        override fun toString() = describe()
    }

    /**
     * Base class for top-level definitions that appear in a schema (Directive and TypeDef).
     */
    sealed class TopLevelDef protected constructor() : DefBase(), ViaductSchema.TopLevelDef

    //
    // "Contained" things:
    // [Arg], [Field] and [EnumValue] and related classes
    //

    sealed class HasDefaultValue protected constructor() : DefBase(), ViaductSchema.HasDefaultValue {
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
        override val holder: HMap = HMap.singleton(null),
        override val description: String? = null,
    ) : Arg(), ViaductSchema.DirectiveArg

    class FieldArg internal constructor(
        override val containingDef: Field,
        override val name: String,
        override val type: ViaductSchema.TypeExpr<TypeDef>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
        override val hasDefault: Boolean,
        override val mDefaultValue: ViaductSchema.Literal?,
        override val holder: HMap = HMap.singleton(null),
        override val description: String? = null,
    ) : Arg(), ViaductSchema.FieldArg

    class EnumValue internal constructor(
        override val containingExtension: ViaductSchema.Extension<Enum, EnumValue>,
        override val name: String,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
        override val holder: HMap = HMap.singleton(null),
        override val description: String? = null,
    ) : DefBase(), ViaductSchema.EnumValue {
        override val containingDef: Enum get() = containingExtension.def
    }

    open class Field internal constructor(
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        override val name: String,
        override val type: ViaductSchema.TypeExpr<TypeDef>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
        override val hasDefault: Boolean,
        override val mDefaultValue: ViaductSchema.Literal?,
        override val holder: HMap = HMap.singleton(null),
        override val description: String? = null,
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
            holder: HMap = HMap.singleton(null),
            description: String? = null,
        ) : this(containingExtension, name, type, appliedDirectives, hasDefault, defaultValue, holder, description, { emptyList() })

        override val args: List<FieldArg> = argsFactory(this)

        override val isOverride: Boolean by lazy { ViaductSchema.isOverride(this) }

        override val containingDef: Record get() = containingExtension.def
    }

    class ObjectField internal constructor(
        override val containingExtension: ViaductSchema.ExtensionWithSupers<Object, ObjectField>,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective<*>>,
        hasDefault: Boolean,
        defaultValue: ViaductSchema.Literal?,
        holder: HMap = HMap.singleton(null),
        description: String? = null,
        argsFactory: (Field) -> List<FieldArg> = { emptyList() },
    ) : Field(
            containingExtension,
            name,
            type,
            appliedDirectives,
            hasDefault,
            defaultValue,
            holder,
            description,
            argsFactory,
        ),
        ViaductSchema.ObjectField {
        override val containingDef: Object get() = containingExtension.def
    }

    //
    // [Directive] concrete class
    //

    class Directive internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val holder: HMap = HMap.singleton(null),
    ) : TopLevelDef(), ViaductSchema.Directive {
        private var mSourceLocation: ViaductSchema.SourceLocation? = null
        private var mIsRepeatable: Boolean? = null
        private var mAllowedLocations: Set<ViaductSchema.Directive.Location>? = null
        private var mArgs: List<DirectiveArg>? = null
        private var mDescription: String? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = guardedGetNullable(mSourceLocation, mArgs)
        override val isRepeatable: Boolean get() = guardedGet(mIsRepeatable)
        override val allowedLocations: Set<ViaductSchema.Directive.Location> get() = guardedGet(mAllowedLocations)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = emptyList()
        override val args: List<DirectiveArg> get() = guardedGet(mArgs)
        override val description: String? get() = mDescription

        internal fun populate(
            isRepeatable: Boolean,
            allowedLocations: Set<ViaductSchema.Directive.Location>,
            sourceLocation: ViaductSchema.SourceLocation?,
            args: List<DirectiveArg>,
            description: String? = null,
        ) {
            check(mArgs == null) { "Directive $name has already been populated; populate() can only be called once" }
            mIsRepeatable = isRepeatable
            mAllowedLocations = allowedLocations
            mSourceLocation = sourceLocation
            mArgs = args
            mDescription = description
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
        override val holder: HMap = HMap.singleton(null),
    ) : TypeDef(), ViaductSchema.Scalar {
        private var mExtensions: List<ViaductSchema.Extension<Scalar, Nothing>>? = null
        private var mDescription: String? = null

        override val extensions: List<ViaductSchema.Extension<Scalar, Nothing>> get() = guardedGet(mExtensions)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = extensions.flatMap { it.appliedDirectives }
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val description: String? get() = mDescription

        internal fun populate(
            extensions: List<ViaductSchema.Extension<Scalar, Nothing>>,
            description: String? = null
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
            mDescription = description
        }
    }

    class Enum internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val holder: HMap = HMap.singleton(null),
    ) : TypeDef(), ViaductSchema.Enum {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.Extension<Enum, EnumValue>>? = null
        private var mValues: List<EnumValue>? = null
        private var mDescription: String? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Enum, EnumValue>> get() = guardedGet(mExtensions)
        override val values: List<EnumValue> get() = guardedGet(mValues)
        override val description: String? get() = mDescription

        internal fun populate(
            extensions: List<ViaductSchema.Extension<Enum, EnumValue>>,
            description: String? = null
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mValues = extensions.flatMap { it.members }
            mDescription = description
        }

        override fun value(name: String) = values.find { name == it.name }
    }

    class Union internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val holder: HMap = HMap.singleton(null),
    ) : TypeDef(), ViaductSchema.Union {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.Extension<Union, Object>>? = null
        private var mPossibleObjectTypes: Set<Object>? = null
        private var mDescription: String? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Union, Object>> get() = guardedGet(mExtensions)
        override val possibleObjectTypes: Set<Object> get() = guardedGet(mPossibleObjectTypes)
        override val description: String? get() = mDescription

        internal fun populate(
            extensions: List<ViaductSchema.Extension<Union, Object>>,
            description: String? = null
        ) {
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
            mDescription = description
        }
    }

    //
    // [Record] and its concrete classes
    //

    sealed interface Record : Def, ViaductSchema.Record {
        override val fields: List<Field>

        override fun field(name: String) = fields.find { name == it.name }

        override fun field(path: Iterable<String>): Field = ViaductSchema.field(this, path)
    }

    sealed class OutputRecord protected constructor(
        final override val containingSchema: SchemaWithData,
    ) : TypeDef(), Record, ViaductSchema.OutputRecord {
        abstract override val extensions: List<ViaductSchema.ExtensionWithSupers<OutputRecord, Field>>
        abstract override val supers: List<Interface>
    }

    class Interface internal constructor(
        containingSchema: SchemaWithData,
        override val name: String,
        override val holder: HMap = HMap.singleton(null),
    ) : OutputRecord(containingSchema), ViaductSchema.Interface {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>>? = null
        private var mFields: List<Field>? = null
        private var mSupers: List<Interface>? = null
        private var mPossibleObjectTypes: Set<Object>? = null
        private var mDescription: String? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val supers: List<Interface> get() = guardedGet(mSupers)
        override val possibleObjectTypes: Set<Object> get() = guardedGet(mPossibleObjectTypes)
        override val description: String? get() = mDescription

        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>>,
            possibleObjectTypes: Set<Object>,
            description: String? = null,
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
            mDescription = description
        }
    }

    class Input internal constructor(
        override val containingSchema: SchemaWithData,
        override val name: String,
        override val holder: HMap = HMap.singleton(null),
    ) : TypeDef(), Record, ViaductSchema.Input {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.Extension<Input, Field>>? = null
        private var mFields: List<Field>? = null
        private var mDescription: String? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Input, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val description: String? get() = mDescription

        internal fun populate(
            extensions: List<ViaductSchema.Extension<Input, Field>>,
            description: String? = null
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mFields = extensions.flatMap { it.members }
            mDescription = description
        }
    }

    class Object internal constructor(
        containingSchema: SchemaWithData,
        override val name: String,
        override val holder: HMap = HMap.singleton(null),
    ) : OutputRecord(containingSchema), ViaductSchema.Object {
        override val possibleObjectTypes = setOf(this)

        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective<*>>? = null
        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Object, ObjectField>>? = null
        private var mFields: List<ObjectField>? = null
        private var mSupers: List<Interface>? = null
        private var mUnions: List<Union>? = null
        private var mDescription: String? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective<*>> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Object, ObjectField>> get() = guardedGet(mExtensions)
        override val fields: List<ObjectField> get() = guardedGet(mFields)
        override val supers: List<Interface> get() = guardedGet(mSupers)
        override val unions: List<Union> get() = guardedGet(mUnions)
        override val description: String? get() = mDescription

        override fun field(name: String): ObjectField? = fields.find { name == it.name }

        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Object, ObjectField>>,
            unions: List<Union>,
            description: String? = null,
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
            mDescription = description
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
