package viaduct.graphql.schema.binary

import graphql.language.NullValue
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector

internal class BSchema(
    directiveCount: Int = 16,
    typeDefCount: Int = 16,
) : ViaductSchema {
    // Freezing the schema

    internal var frozen: Boolean = false

    /**
     * Freeze schema, meaning mutations are no longer allowed.
     * This is an idempotent operation, ie, the second
     * and subsequent invocations have no further effect.
     */
    fun freeze() {
        frozen = true
    }

    /** Throws an exception if schema has been frozen. */
    internal fun checkMutation() {
        check(!frozen) {
            "Schema has been frozen: mutations are no longer possible."
        }
    }

    //
    // Properties for [BSchema] root-type defs

    override var queryTypeDef: Object? = null
        internal set(value) {
            checkMutation()
            field = value
        }

    override var mutationTypeDef: Object? = null
        internal set(value) {
            checkMutation()
            field = value
        }

    override var subscriptionTypeDef: Object? = null
        internal set(value) {
            checkMutation()
            field = value
        }

    /**
     * Validates that all applied directives reference existing directive definitions
     * and that their arguments match the definition.
     */
    internal fun validateAppliedDirectives(
        appliedDirectives: Collection<ViaductSchema.AppliedDirective>,
        context: String
    ) {
        for (applied in appliedDirectives) {
            val definition = mDirectives[applied.name]
                ?: throw InvalidSchemaException(
                    "Applied directive @${applied.name} on $context references non-existent directive definition"
                )
            // Validate that all arguments in applied directive exist in definition
            for (argName in applied.arguments.keys) {
                if (definition.args.none { it.name == argName }) {
                    throw InvalidSchemaException(
                        "Applied directive @${applied.name} on $context has argument '$argName' " +
                            "not defined in directive definition"
                    )
                }
            }
        }
    }

    //
    // Properties and functions for [BSchema.directives] and [BSchema.types]

    private val mDirectives = LinkedHashMap<String, Directive>(((directiveCount / 0.75f) + 1).toInt(), 0.75f)
    override val directives: Map<String, Directive> = mDirectives

    private val mTypes = LinkedHashMap<String, TypeDef>(((typeDefCount / 0.75f) + 1).toInt(), 0.75f)
    override val types: Map<String, TypeDef> = mTypes

    fun makeDirective(name: String) =
        Directive(this, name).also {
            checkMutation()
            mDirectives[name] = it
        }

    inline fun <reified T : TypeDef> addTypeDef(name: String): T {
        checkMutation()
        return T::class.java.getConstructor(BSchema::class.java, String::class.java).newInstance(this, name).also {
            mTypes[name] = it
        }
    }

    /** Throws NoSuchElementException if not found. */
    fun findDirective(name: String): Directive = mDirectives[name] ?: throw NoSuchElementException("Directive def not found ($name).")

    /** Throws NoSuchElementException if not found, ClassCastException if not right type. */
    inline fun <reified T : TypeDef> findType(name: String): T {
        val result = mTypes[name] ?: throw NoSuchElementException("Type def not found ($name).")
        return result as T
    }

    //
    // [Def] related interfaces and classes

    sealed interface Def : ViaductSchema.Def {
        override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }
    }

    /**
     * Marker interface for top-level definitions that appear a schema (Directive and TypeDef).
     */
    sealed interface TopLevelDef : Def

    //
    // "Contained" things:
    // [Arg], [Field] and [EnumValue] and related interfaces and classes

    sealed class HasDefaultValue(
        override val name: String,
        override val type: TypeExpr,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        override val hasDefault: Boolean,
        private val mDefaultValue: Any?,
    ) : ViaductSchema.HasDefaultValue, Def {
        // Leave abstract so we can narrow the type
        abstract override val containingDef: Def

        override val defaultValue: Any?
            get() =
                if (hasDefault) {
                    if (mDefaultValue is NullValue) null else mDefaultValue
                } else {
                    throw NoSuchElementException("No default value for ${this.describe()}")
                }
    }

    sealed class Arg(
        name: String,
        type: TypeExpr,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : ViaductSchema.Arg, HasDefaultValue(name, type, appliedDirectives, hasDefault, defaultValue)

    interface HasArgs : ViaductSchema.HasArgs, Def {
        override val args: List<Arg>
    }

    class DirectiveArg(
        override val containingDef: Directive,
        name: String,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        type: TypeExpr,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : ViaductSchema.DirectiveArg, Arg(name, type, appliedDirectives, hasDefault, defaultValue) {
        override fun toString() = describe()
    }

    class FieldArg internal constructor(
        override val containingDef: Field,
        name: String,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        type: TypeExpr,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : ViaductSchema.FieldArg, Arg(name, type, appliedDirectives, hasDefault, defaultValue) {
        override fun toString() = describe()
    }

    class EnumValue internal constructor(
        override val containingExtension: Extension<Enum, EnumValue>,
        override val name: String,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
    ) : ViaductSchema.EnumValue, Def {
        override fun toString() = describe()

        override val containingDef get() = containingExtension.def
    }

    class Field(
        override val containingExtension: Extension<Record, Field>,
        override val name: String,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        override val type: TypeExpr,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : ViaductSchema.Field, HasArgs, HasDefaultValue(name, type, appliedDirectives, hasDefault, defaultValue) {
        override fun toString() = describe()

        override var args = emptyList<FieldArg>()
            internal set(value) {
                containingDef.owner.checkMutation()
                field = value
            }

        override val isOverride: Boolean by lazy { ViaductSchema.isOverride(this) }

        override val containingDef get() = containingExtension.def
    }

    //
    // [Directive] concrete class

    class Directive(
        val owner: BSchema,
        override val name: String,
    ) : ViaductSchema.Directive, TopLevelDef, HasArgs {
        override fun toString() = describe()

        override var sourceLocation: ViaductSchema.SourceLocation? = null
            internal set(value) {
                owner.checkMutation()
                field = value
            }

        // Always empty for Directives
        override val appliedDirectives = emptyList<ViaductSchema.AppliedDirective>()

        override var isRepeatable: Boolean = false
            internal set(value) {
                owner.checkMutation()
                field = value
            }

        override var allowedLocations = emptySet<ViaductSchema.Directive.Location>()
            internal set(value) {
                owner.checkMutation()
                field = value
            }

        override var args = emptyList<DirectiveArg>()
            internal set(value) {
                owner.checkMutation()
                field = value
            }
    }

    //
    // [TypeDef] related interfaces and abstract classes

    sealed interface TypeDef : ViaductSchema.TypeDef, TopLevelDef {
        override fun asTypeExpr() = TypeExpr(this)

        val owner: BSchema

        override val possibleObjectTypes: Set<Object>
    }

    sealed class TypeDefImpl(
        override val owner: BSchema,
        override val name: String,
    ) : TypeDef {
        open override val possibleObjectTypes = emptySet<Object>()
    }

    //
    // [Extension] related types

    sealed class HasExtensionsImpl<D : TypeDef, M : Def>(
        owner: BSchema,
        name: String,
    ) : TypeDefImpl(owner, name) {
        protected var mSourceLocation: ViaductSchema.SourceLocation? = null

        private var mAppliedDirectives = emptyList<ViaductSchema.AppliedDirective>()
        override val appliedDirectives get() = mAppliedDirectives

        protected open fun updatedExtensions(newExtensions: Collection<Extension<D, M>>) {
            mSourceLocation = newExtensions.first().sourceLocation
            mAppliedDirectives = newExtensions.flatMap { it.appliedDirectives }
            // Validate applied directives on extensions
            for (ext in newExtensions) {
                owner.validateAppliedDirectives(ext.appliedDirectives, "type $name")
                // Validate applied directives on members (fields or enum values)
                for (member in ext.members) {
                    owner.validateAppliedDirectives(member.appliedDirectives, "${member.describe()}")
                }
            }
        }
    }

    class Extension<out D : TypeDef, M : Def>(
        override val def: D,
        override val appliedDirectives: Collection<ViaductSchema.AppliedDirective>,
        override val sourceLocation: ViaductSchema.SourceLocation?,
        override val isBase: Boolean,
        initialMembers: List<M> = emptyList(),
        override val supers: List<Interface> = emptyList(),
    ) : ViaductSchema.Extension<D, M>, ViaductSchema.ExtensionWithSupers<D, M> {
        init {
            // Validate that all supers are actually Interface types
            // (unsafe casts can bypass compile-time checking)
            // Cast to List<*> to avoid ClassCastException during iteration
            @Suppress("UNCHECKED_CAST")
            val supersAsAny = supers as List<*>
            for (superType in supersAsAny) {
                if (superType != null && superType !is Interface) {
                    val typeName = (superType as? TypeDef)?.name ?: superType.toString()
                    throw InvalidSchemaException(
                        "Type ${def.name} implements $typeName which is not an Interface type " +
                            "(got ${superType.javaClass.simpleName})"
                    )
                }
            }
        }

        override var members: List<M> = initialMembers
            internal set(value) {
                def.owner.checkMutation()
                // Validate applied directives on members
                for (member in value) {
                    def.owner.validateAppliedDirectives(member.appliedDirectives, "${member.describe()}")
                }
                field = value
            }
    }

    //
    // Non-[Record] [TypeDef] concrete classes

    class Enum(
        owner: BSchema,
        name: String
    ) : ViaductSchema.Enum, HasExtensionsImpl<Enum, EnumValue>(owner, name) {
        override fun toString() = describe()

        override val sourceLocation get() = mSourceLocation

        override var values: Collection<EnumValue> = emptyList()
            private set

        override var extensions: Collection<Extension<Enum, EnumValue>> = emptyList()
            internal set(value) {
                owner.checkMutation()
                if (value.isEmpty()) {
                    throw InvalidFileFormatException("Types must have at least one extension ($this).")
                }
                field = value
                updatedExtensions(extensions)
                values = value.flatMap { it.members }
            }

        override fun value(name: String) = values.find { name == it.name }
    }

    class Scalar(
        owner: BSchema,
        name: String
    ) : ViaductSchema.Scalar, TypeDefImpl(owner, name) {
        override fun toString() = describe()

        override var sourceLocation: ViaductSchema.SourceLocation? = null
            internal set(value) {
                owner.checkMutation()
                field = value
            }

        override var appliedDirectives = emptyList<ViaductSchema.AppliedDirective>()
            internal set(value) {
                owner.checkMutation()
                owner.validateAppliedDirectives(value, "scalar $name")
                field = value
            }
    }

    class Union(
        owner: BSchema,
        name: String
    ) : ViaductSchema.Union, HasExtensionsImpl<Union, Object>(owner, name) {
        override fun toString() = describe()

        override var possibleObjectTypes: Set<Object> = emptySet<BSchema.Object>()
            private set

        override var extensions: Collection<Extension<Union, Object>> = emptyList()
            internal set(value) {
                owner.checkMutation()
                if (value.isEmpty()) {
                    throw InvalidFileFormatException("Types must have at least one extension ($this).")
                }
                // Validate that all members are actually Object types
                for (ext in value) {
                    @Suppress("UNCHECKED_CAST")
                    val membersAsAny = ext.members as List<*>
                    for (member in membersAsAny) {
                        if (member != null && member !is Object) {
                            val typeName = (member as? TypeDef)?.name ?: member.toString()
                            throw InvalidSchemaException(
                                "Union $name contains $typeName which is not an Object type " +
                                    "(got ${member.javaClass.simpleName})"
                            )
                        }
                    }
                }
                field = value
                updatedExtensions(value)
                possibleObjectTypes = value.flatMap { it.members }.toSet()
            }
    }

    //
    // [Record] and its concrete classes

    sealed class Record(
        owner: BSchema,
        name: String
    ) : ViaductSchema.Record, HasExtensionsImpl<Record, Field>(owner, name) {
        override val sourceLocation get() = mSourceLocation

        override fun field(name: String) = fields.find { name == it.name }

        override fun field(path: Iterable<String>): Field = ViaductSchema.field(this, path)

        protected var mFields = emptyList<Field>()
        override val fields get() = mFields

        protected var mSupers = emptyList<Interface>()
        override val supers get() = mSupers

        override val unions = emptyList<Union>()
    }

    class Interface(
        owner: BSchema,
        name: String
    ) : ViaductSchema.Interface, Record(owner, name) {
        override var possibleObjectTypes = emptySet<Object>()
            internal set(value) {
                owner.checkMutation()
                // Validate that all elements are actually Object types
                @Suppress("UNCHECKED_CAST")
                val valueAsAny = value as Set<*>
                for (obj in valueAsAny) {
                    if (obj != null && obj !is Object) {
                        val typeName = (obj as? TypeDef)?.name ?: obj.toString()
                        throw InvalidSchemaException(
                            "Interface $name possibleObjectTypes contains $typeName which is not an Object type " +
                                "(got ${obj.javaClass.simpleName})"
                        )
                    }
                }
                field = value
            }

        override var extensions: Collection<Extension<Interface, Field>> = emptyList()
            internal set(value) {
                owner.checkMutation()
                if (value.isEmpty()) {
                    throw InvalidFileFormatException("Types must have at least one extension ($this).")
                }
                field = value
                updatedExtensions(value)
                mFields = value.flatMap { it.members }
                mSupers = value.flatMap { it.supers }
            }

        override fun toString() = describe()
    }

    class Input(
        owner: BSchema,
        name: String
    ) : ViaductSchema.Input, Record(owner, name) {
        override var extensions: Collection<Extension<Input, Field>> = emptyList()
            internal set(value) {
                owner.checkMutation()
                if (value.isEmpty()) {
                    throw InvalidFileFormatException("Types must have at least one extension ($this).")
                }
                field = value
                updatedExtensions(value)
                mFields = value.flatMap { it.members }
            }

        override fun toString() = describe()
    }

    class Object(
        owner: BSchema,
        name: String
    ) : ViaductSchema.Object, Record(owner, name) {
        override val possibleObjectTypes = setOf(this)

        override var unions = emptyList<Union>()
            internal set(value) {
                owner.checkMutation()
                field = value
            }

        override var extensions: Collection<Extension<Object, Field>> = emptyList()
            internal set(value) {
                owner.checkMutation()
                if (value.isEmpty()) {
                    throw InvalidFileFormatException("Types must have at least one extension ($this).")
                }
                field = value
                updatedExtensions(value)
                mFields = value.flatMap { it.members }
                mSupers = value.flatMap { it.supers }
            }

        override fun toString() = describe()
    }

    data class TypeExpr(
        override val baseTypeDef: TypeDef,
        override val baseTypeNullable: Boolean = true,
        override val listNullable: BitVector = ViaductSchema.TypeExpr.NO_WRAPPERS
    ) : ViaductSchema.TypeExpr() {
        // isSimple, isList, isNullable, listDepth, and nullableAtDepth are inherited from parent class

        override fun unwrapLists() = copy(listNullable = ViaductSchema.TypeExpr.NO_WRAPPERS)

        override fun unwrapList() =
            if (listNullable.size == 0) {
                null
            } else {
                copy(listNullable = listNullable.lsr())
            }
    }
}
