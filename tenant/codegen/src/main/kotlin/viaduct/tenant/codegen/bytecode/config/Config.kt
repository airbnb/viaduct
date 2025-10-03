@file:Suppress("ClassName", "ClassNaming", "MatchingDeclarationName")

package viaduct.tenant.codegen.bytecode.config

import kotlinx.metadata.KmClass
import kotlinx.metadata.Visibility
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.visibility
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.JavaName
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.graphql.schema.baseGraphqlScalarTypeMapping
import viaduct.utils.string.capitalize

// Utilities expressed as extension functions go in GRTUtils.kt.  Constants and
// other utility functions are found in this file.

// In the future some or all of these may need to be injectable, which
// is why we have an object for them, but for now hard-wiring is fine.
object cfg {
    val INPUT_BASE_INTERFACE = KmName("com/airbnb/viaduct/schema/base/ViaductInputType")

    val JSON_CREATOR_ANNOTATION = "com/fasterxml/jackson/annotation/JsonCreator"
    val JSON_DESERIALIZE_ANNOTATION = "com/fasterxml/jackson/databind/annotation/JsonDeserialize"
    val JSON_IGNORE_ANNOTATION = "com/fasterxml/jackson/annotation/JsonIgnore"
    val JSON_IGNORE_PROPERTIES_ANNOTATION = "com/fasterxml/jackson/annotation/JsonIgnoreProperties"
    val JSON_INCLUDE_ANNOTATION = "com/fasterxml/jackson/annotation/JsonInclude"
    val JSON_POJO_BUILDER_ANNOTATION = "com/fasterxml/jackson/databind/annotation/JsonPOJOBuilder"
    val JSON_PROPERTY_ANNOTATION = "com/fasterxml/jackson/annotation/JsonProperty"
    val JSON_PROPERTY_ORDER_ANNNOTATION = "com/fasterxml/jackson/annotation/JsonPropertyOrder"

    fun argumentTypeName(field: ViaductExtendedSchema.Field): String = argumentTypeName(field.containingDef.name, field.name)

    fun argumentTypeName(
        typeName: String,
        fieldName: String
    ): String = "${typeName}_${fieldName.capitalize()}_Arguments"

    fun needsQuerySelections(field: ViaductExtendedSchema.Field) = field.hasAppliedDirective("component")

    fun isHasClearableFieldsInputType(def: ViaductExtendedSchema.TypeDef) = def.hasAppliedDirective("hasClearableFields")

    val QUERY_SELECTIONS_FIELD_NAME = "querySelections"

    val EDGES_QUERY_RESPONSE =
        JavaBinaryName("com.airbnb.viaduct.loaders.core.edges.EdgesQueryResponse")

    val REFLECTION_NAME = "Reflection"

    /** Scalars and some non-scalar types (e.g., UGCText) are mapped
     *  to Kotlin types outside of the standard "schema.generated"
     *  package.  Types not in this map use standard names.
     */
    private val graphqlScalarTypeToKmName: Map<String, KmName> =
        baseGraphqlScalarTypeMapping.mapValues { KmName(it.value.qualifiedName!!.replace(".", "/")) }

    fun nativeGraphQLTypeToKmName(baseTypeMapper: BaseTypeMapper): Map<String, KmName> = graphqlScalarTypeToKmName + baseTypeMapper.getAdditionalTypeMapping()

    val typesThatNeedDefaultResolveFieldMethod = setOf(
        "ExperienceCategory",
        "ExperienceCancellationPolicy",
        "ExperienceListingStatus",
        "ExperienceSupplyListing",
        "ExperienceSupplyListingStatusInfo",
        "StayPDPSections",
        "TrustSDUI",
        "TrustSDUIComponent",
        "TrustSDUIComponentAction",
        "TrustSDUIScreenLogging",
        "TrustSDUIToolbar"
    )

    /** Classes to be referenced by unqualified names
     *  in Java code given to Javassist for compilation. */
    val importedClasses = listOf(
        JavaName("java.util.Arrays"), // Used in Java expressions for default values
    )
    val BUILD_TIME_MODULE_EXTRACTOR = Regex("modules/(.*?)/schema")
    val TEST_TIME_MODULE_EXTRACTOR = Regex("/graphql/([^/]*)")

    /** Used by [ViaductExtendedSchema.Field.crossModule] to extract the module name
     *  from a GraphQL type's source location.  Unfortunately, different
     *  patterns are needed for the bytecode-generator (which uses a Bazel
     *  target to read the central schema) and testing code (which uses a
     *  resource file).  This variable is initialized to the build-time
     *  pattern; testing-code can change it to the test-time pattern on
     *  startup.
     */
    var moduleExtractor: Regex = BUILD_TIME_MODULE_EXTRACTOR

    var excludedCrossModuleField: Boolean = true

    val cannotCreateValue = setOf(
        "CanvasHydrationForm", // Cycle
        "CanvasContentBuilderPresentationContainer", // Cycle
        "CanvasPresentationForm", // Trips up cycle detector
        "ContentBuilderElement", // Cycle
        "ContentBuilderAttributeGroup", // Cycle
        "CarsonRecomPortalStrategy", // Trips up cycle detector
        "CarsonRecomPortalConfig", // Cycle
        "CarsonRecomPortalBucketStrategy", // Cycle
        "CarsonRecomPortalBucket", // Cycle
        "NavigateToPanels", // Contains a non-nullable interface with no members (replacePanels)
    )

    fun getConstructorArgOrder(clazz: Class<*>): List<String> = metaCache.getConstructorArgOrder(clazz)

    // ======== Metadata support ======

    private class MetadataHelper {
        private val metadataMap: MutableMap<Class<*>, KmClass> = mutableMapOf()

        fun getConstructorArgOrder(clazz: Class<*>): List<String> {
            val kmClass = getMetadata(clazz)
            val ctors = kmClass.constructors.filter {
                it.valueParameters.size != 0 &&
                    (it.visibility == Visibility.PUBLIC || it.visibility == Visibility.INTERNAL)
            }
            if (ctors.size == 0) return emptyList()
            val ctor = ctors.minByOrNull { it.valueParameters.size }!! // The largest ctor is the one that handles default values
            return ctor.valueParameters.map { it.name }
        }

        fun getMetadata(clazz: Class<*>): KmClass =
            metadataMap.getOrPut(clazz) {
                @Suppress("UNCHECKED_CAST")
                val metadataList = clazz.annotations.filter { it is Metadata } as List<Metadata>
                if (metadataList.size != 1) throw IllegalStateException("${clazz.simpleName} wrong @Metadata count (${metadataList.size}")
                val metadata = metadataList.get(0)
                (KotlinClassMetadata.readStrict(metadata) as KotlinClassMetadata.Class).kmClass
            }
    }

    private val metaCache = MetadataHelper()

    // ======== V2 GRTs ======
    val ARGUMENTS_GRT = JavaBinaryName("viaduct.api.types.Arguments")
    val COMPOSITE_OUTPUT_GRT = JavaBinaryName("viaduct.api.types.CompositeOutput")
    val ENUM_GRT = JavaBinaryName("viaduct.api.types.Enum")
    val INPUT_GRT = JavaBinaryName("viaduct.api.types.Input")
    val INTERFACE_GRT = JavaBinaryName("viaduct.api.types.Interface")
    val NODE_COMPOSITE_OUTPUT_GRT = JavaBinaryName("viaduct.api.types.NodeCompositeOutput")
    val NODE_OBJECT_GRT = JavaBinaryName("viaduct.api.types.NodeObject")
    val QUERY_OBJECT_GRT = JavaBinaryName("viaduct.api.types.Query")
    val MUTATION_OBJECT_GRT = JavaBinaryName("viaduct.api.types.Mutation")
    val OBJECT_GRT = JavaBinaryName("viaduct.api.types.Object")
    val UNION_GRT = JavaBinaryName("viaduct.api.types.Union")
    val GRT = JavaBinaryName("viaduct.api.types.GRT")

    /** Interface implemented by v2 object types */
    val OBJECT_BASE =
        JavaBinaryName("viaduct.api.internal.ObjectBase")
    val OBJECT_BASE_BUILDER =
        JavaBinaryName("viaduct.api.internal.ObjectBase\$Builder")
    val ENGINE_OBJECT_DATA =
        JavaBinaryName("viaduct.engine.api.EngineObjectData")
    val EXECUTION_CONTEXT =
        JavaBinaryName("viaduct.api.context.ExecutionContext")
    val INTERNAL_CONTEXT =
        JavaBinaryName("viaduct.api.internal.InternalContext")
    val INPUT_LIKE_BASE =
        JavaBinaryName("viaduct.api.internal.InputLikeBase")
    val INPUT_LIKE_BASE_BUILDER =
        JavaBinaryName("viaduct.api.internal.InputLikeBase\$Builder")

    // Path to the tenant API module from the oss root
    val TENANT_API_MODULE_PATH = "tenant/api"
    val REFLECTED_TYPE =
        JavaBinaryName("viaduct.api.reflect.Type")
    val REFLECTED_FIELD =
        JavaBinaryName("viaduct.api.reflect.Field")
    val REFLECTED_FIELD_IMPL =
        JavaBinaryName("viaduct.api.internal.FieldImpl")
    val REFLECTED_COMPOSITE_FIELD =
        JavaBinaryName("viaduct.api.reflect.CompositeField")
    val REFLECTED_COMPOSITE_FIELD_IMPL =
        JavaBinaryName("viaduct.api.internal.CompositeFieldImpl")
}
