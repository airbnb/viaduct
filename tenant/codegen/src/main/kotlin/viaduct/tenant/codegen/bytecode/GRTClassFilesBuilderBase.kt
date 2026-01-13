package viaduct.tenant.codegen.bytecode

import java.io.File
import viaduct.apiannotations.TestingApi
import viaduct.codegen.km.KmClassFilesBuilder
import viaduct.codegen.utils.JavaName
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.invariants.InvariantChecker
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.hashForSharding

/** This class represents the public API to the bytecode generator.  Everything
 *  else in this package (or its subpackages) is either internal or public for
 *  testing purposes.
 *
 *  An instance of this class is created by calling one of the factory methods
 *  (e.g., [classicBuilderFrom]).  The factory method will construct and initialize
 *  the code generator.  You then call [addAll], which will generate the classfiles
 *  into memory.  You then call [buildClassfiles] to write the classfiles to disk.
 *
 *  For implementors:
 *
 *  If you want to implement a variant family of GRTs, you write a subclass of
 *  this class and provide a factory method for your subclass.  See [V0_9ClassFilesBuilder]
 *  for a sample implementation.
 *
 */
abstract class GRTClassFilesBuilderBase protected constructor(
    protected val args: CodeGenArgs,
) {
    internal val baseTypeMapper: BaseTypeMapper get() = args.baseTypeMapper

    protected val ViaductSchema.TypeDef.isInShard
        get() =
            hashForSharding() % args.workerCount == args.workerNumber

    private var isLoaded = false

    /**
     * The schema being processed. Available after [addAll] is called.
     */
    protected lateinit var schema: ViaductSchema
        private set

    /**
     * Returns true if the given object type is the query root type in the schema.
     */
    internal fun ViaductSchema.Object.isQueryType(): Boolean = this === schema.queryTypeDef

    /**
     * Returns true if the given object type is the mutation root type in the schema.
     */
    internal fun ViaductSchema.Object.isMutationType(): Boolean = this === schema.mutationTypeDef

    /**
     * Returns true if the given object type is the subscription root type in the schema.
     */
    internal fun ViaductSchema.Object.isSubscriptionType(): Boolean = this === schema.subscriptionTypeDef

    /**
     * Returns true if the given object type is any root type (query, mutation, or subscription).
     */
    internal fun ViaductSchema.Object.isRootType(): Boolean = isQueryType() || isMutationType() || isSubscriptionType()

    /**
     * Initialize the schema for tests that call individual gen methods directly without going through [addAll].
     * This is only for testing purposes.
     */
    internal fun initSchemaForTest(schema: ViaductSchema) {
        this.schema = schema
    }

    fun buildClassfiles(outputRoot: File) {
        if (!isLoaded) throw IllegalStateException("Must call addAll first.")
        kmClassFilesBuilder.buildClassfiles(outputRoot)
    }

    fun buildClassLoader(): ClassLoader {
        if (!isLoaded) throw IllegalStateException("Must call addAll first.")
        return kmClassFilesBuilder.buildClassLoader()
    }

    fun addAll(schema: ViaductSchema): GRTClassFilesBuilderBase {
        if (isLoaded) throw IllegalStateException("Can't call addAll twice.")
        this.schema = schema
        setup()
        for (def in schema.types.values) {
            if (!def.isInShard) continue
            when (def) {
                is ViaductSchema.Enum -> addEnum(def)
                is ViaductSchema.Input -> addInput(def)
                is ViaductSchema.Interface -> addInterface(def)
                is ViaductSchema.Object -> addObject(def)
                is ViaductSchema.Scalar -> { // skip
                }

                is ViaductSchema.Union -> addUnion(def)
                else -> throw IllegalArgumentException("Can't handle $def")
            }
        }
        subclassAddAll(schema)

        isLoaded = true
        return this
    }

    /**
     * An optional hook that allows subclasses to handle the entire schema.
     *
     * Will be called after individual type adders (e.g. [addEnum]) have been called
     * for each type in the schema.
     */
    protected open fun subclassAddAll(schema: ViaductSchema) {}

    protected abstract fun addEnum(def: ViaductSchema.Enum)

    protected abstract fun addInput(def: ViaductSchema.Input)

    protected abstract fun addInterface(def: ViaductSchema.Interface)

    protected abstract fun addObject(def: ViaductSchema.Object)

    protected abstract fun addUnion(def: ViaductSchema.Union)

    /** Return true if the codegen algorithm generates a GRT for this
     *  type.  This method is used by [addSchemaGRTReference] to determine
     *  when a placeholder is needed.  `addXyz` methods will be called
     *  whether or not this is true: they need to check internally if they
     *  should generate or not.  (NOTE: the fact that we don't use this
     *  in [addAll] was required to fully implement the logic for
     *  `_Argument` types in [addOject].)
     */
    protected abstract fun isGenerated(def: ViaductSchema.TypeDef): Boolean

    /** First thing [addAll] does is call this function to allow subclasses
     *  to further initialize themselves if needed.
     */
    protected open fun setup() {}

    val kmClassFilesBuilder = KmClassFilesBuilder(args.javaTargetVersion, args.timer)

    val pkg = JavaName(args.pkgForGeneratedClasses).asKmName

    /**
     * Given a [def], if [isGenerated] returns false for it or if it will be generated by
     * another worker, then create a placeholder class for it so
     * that it's available to the Javassist compiler.
     *
     * Important! - this will only create an empty shell class in the classpool. This means the following scenarios
     * will NOT work as intended:
     *    - If you're generating a class that implements [def] as an interface, make sure the real [def] has no
     *      non-abstract methods, and thus no DefaultImpls. Otherwise, your generated class will be missing the
     *      DefaultImpls methods.
     *    - If you're generating a method body that calls one of [def]'s methods, Javassist won't be able to compile it
     */
    fun addSchemaGRTReference(def: ViaductSchema.TypeDef) {
        if (def.isInShard && isGenerated(def)) return

        val fqn = def.name.kmFQN(pkg)

        baseTypeMapper.addSchemaGRTReference(def, fqn, kmClassFilesBuilder)
    }

    @TestingApi
    fun checkInvariants(
        check: InvariantChecker = InvariantChecker(),
        allowedSuperTypes: Set<KmName>
    ): InvariantChecker = kmClassFilesBuilder.checkInvariants(check = check, allowedSuperTypes = allowedSuperTypes)

    companion object {
        fun builderFrom(args: CodeGenArgs): GRTClassFilesBuilderBase = GRTClassFilesBuilder(args)
    }
}
