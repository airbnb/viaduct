package viaduct.tenant.codegen.bytecode.config

import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmVariance
import kotlinx.metadata.isNullable
import viaduct.codegen.km.KmClassFilesBuilder
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.tenant.codegen.bytecode.config.cfg.REFLECTION_NAME

/**
 * Interface for handling base type mapping in ViaductSchemaExtensions.
 * This allows AirBnB-specific type mapping logic to be plugged in.
 */
interface BaseTypeMapper {
    fun mapBaseType(
        type: ViaductExtendedSchema.TypeExpr,
        pkg: KmName,
        field: ViaductExtendedSchema.HasDefaultValue? = null,
        isInput: Boolean = false,
    ): KmType?

    /**
     * Determines variance for input types based on TypeDef kind.
     * Returns null if default OSS behavior should be used.
     */
    fun getInputVarianceForObject(): KmVariance?

    /**
     * Determines if GlobalID type alias should be used for ID types.
     * Returns true if GlobalID type alias should be used (non-modern builds).
     */
    fun useGlobalIdTypeAlias(): Boolean

    /**
     * Adds external class reference for the given type definition to the KmClassFilesBuilder.
     * This encapsulates the logic for handling different type kinds and build modes.
     */
    fun addSchemaGRTReference(
        def: ViaductExtendedSchema.TypeDef,
        fqn: KmName,
        kmClassFilesBuilder: KmClassFilesBuilder
    )

    /**
     * Provides additional GraphQL type to KmName mappings specific to this mapper.
     */
    fun getAdditionalTypeMapping(): Map<String, KmName>

    /**
     * Provides the GlobalID type appropriate for this mapper.
     * Modern mode uses viaduct.api.globalid.GlobalID (parameterized type).
     */
    fun getGlobalIdType(): JavaBinaryName
}

/**
 * Viaduct implementation of BaseTypeMapper that handles standard cases.
 */
class ViaductBaseTypeMapper(
    val schema: ViaductExtendedSchema,
) : BaseTypeMapper {
    override fun mapBaseType(
        type: ViaductExtendedSchema.TypeExpr,
        pkg: KmName,
        field: ViaductExtendedSchema.HasDefaultValue?,
        isInput: Boolean,
    ): KmType? {
        val baseTypeDef = type.baseTypeDef

        // Handle standard Viaduct cases
        if (baseTypeDef.isBackingDataType) {
            return type.backingDataType()
        } else if (baseTypeDef.isID) {
            return type.idKmType(pkg, field, isInput)
        }

        return null // Let extension function handle default case
    }

    override fun getInputVarianceForObject(): KmVariance? {
        // Viaduct uses INVARIANT for modern builds (no isModern flag in OSS)
        return KmVariance.INVARIANT
    }

    override fun useGlobalIdTypeAlias(): Boolean {
        // Viaduct (OSS) doesn't use GlobalID type alias
        return false
    }

    override fun addSchemaGRTReference(
        def: ViaductExtendedSchema.TypeDef,
        fqn: KmName,
        kmClassFilesBuilder: KmClassFilesBuilder
    ) {
        val nested = mutableListOf<JavaIdName>()

        // Modern mode: add Reflection nested class for types with reflected types
        if (def.hasReflectedType) {
            nested += JavaIdName(REFLECTION_NAME)
        }

        when (def) {
            is ViaductExtendedSchema.Object -> {
                // Modern mode: objects are treated as classes (not interfaces)
                kmClassFilesBuilder.addExternalClassReference(fqn, nested = nested)
            }
            is ViaductExtendedSchema.Interface, is ViaductExtendedSchema.Union -> {
                kmClassFilesBuilder.addExternalClassReference(fqn, isInterface = true, nested = nested)
            }
            is ViaductExtendedSchema.Input, is ViaductExtendedSchema.Enum -> {
                kmClassFilesBuilder.addExternalClassReference(fqn, nested = nested)
            }
        }
    }

    override fun getAdditionalTypeMapping(): Map<String, KmName> {
        // Modern/OSS mode uses parameterized GlobalID, no additional mapping needed
        return emptyMap()
    }

    override fun getGlobalIdType(): JavaBinaryName {
        return JavaBinaryName("viaduct.api.globalid.GlobalID")
    }

    /**
     * Constructs a KmType for a TypeExpr representing an ID scalar.
     * For `Foo` a GraphQL node-composite-output type, when:
     *   ID is not the `id` of a node-type and has no @idOf on it -> String
     *   isInput == false || Foo is Object -> viaduct.api.globalid.GlobalID<Foo>
     *   else -> viaduct.api.globalid.GlobalID<out Foo>
     *
     * See the `learnings.md` section on "input types" for more information.
     *
     * @param pkg containing generated GRTs
     * @param field field this type-expr is from, it that's where it's from
     * @param isInput type is being used in an input context, e.g., as
     *   setter for a field or the continuation param of a suspend fun
     */
    internal fun ViaductExtendedSchema.TypeExpr.idKmType(
        pkg: KmName,
        field: ViaductExtendedSchema.HasDefaultValue?,
        isInput: Boolean = false,
    ): KmType {
        val idTypeName = this@ViaductBaseTypeMapper.getGlobalIdType().asKmName // The "GlobalID" in GlobalID<Foo>
        val grtTypeName = field?.grtNameForIdParam() // The "Foo" in GlobalID<Foo>

        if (grtTypeName == null || schema.types[grtTypeName] == null) {
            return KmType().also {
                it.classifier = KmClassifier.Class(Km.STRING.toString())
                it.isNullable = this.baseTypeNullable
                if (this@ViaductBaseTypeMapper.useGlobalIdTypeAlias()) {
                    it.abbreviatedType = KmType().also {
                        it.classifier = KmClassifier.TypeAlias(idTypeName.toString())
                    }
                }
            }
        }

        // TODO be strict about idOf references (https://app.asana.com/1/150975571430/project/1207604899751448/task/1211505368235519)
        //   (exception here doesn't get thrown because of null-check in if-statement above -- remove that eventually)
        val grtBaseTypeDef = schema.types[grtTypeName]
            ?: throw IllegalStateException(
                "Bad validation: @idOf most likely not checked" +
                    " (${field.containingDef.name}.${field.name} @idOf: $grtTypeName)" +
                    " Schema: ${schema.types.keys.joinToString(",")}"
            )

        val notGraphQLObjectType = (grtBaseTypeDef.kind != ViaductExtendedSchema.TypeDefKind.OBJECT)

        val variance = if (isInput && notGraphQLObjectType) {
            KmVariance.OUT
        } else {
            KmVariance.INVARIANT
        }

        return idTypeName.asType().also {
            it.arguments += KmTypeProjection(variance, pkg.append("/$grtTypeName").asType())
            it.isNullable = this.baseTypeNullable
        }
    }
}
