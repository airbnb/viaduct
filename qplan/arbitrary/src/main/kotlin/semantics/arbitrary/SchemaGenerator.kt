package semantics.arbitrary

import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.Directive
import graphql.language.EnumTypeDefinition
import graphql.language.EnumValueDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputValueDefinition
import graphql.language.IntValue
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import java.math.BigInteger

internal const val GENERATED_HASH_TYPE = "Hash"
internal const val GENERATED_HASH_FIELD = "hash"
internal const val GENERATED_HASH_NESTED_FIELD = "nested"
internal const val GENERATED_PARENT_ROOT_TYPE = "GeneratedParentRoot"
internal const val GENERATED_PARENT_CHILD_TYPE = "GeneratedParentChild"
internal const val GENERATED_PARENT_GRANDCHILD_TYPE = "GeneratedParentGrandchild"
internal const val GENERATED_PARENT_GREAT_GRANDCHILD_TYPE = "GeneratedParentGreatGrandchild"
internal const val GENERATED_PARENT_ROOT_FIELD = "generatedParentRoot"
internal const val GENERATED_PARENT_CHILD_FIELD = "child"
internal const val GENERATED_PARENT_FIELD = "parent"
internal const val GENERATED_PARENT_VALUE_FIELD = "ancestorValue"
internal const val GENERATED_PARENT_RESULT_FIELD = "result"
internal const val GENERATED_RANDOM_PARENT_TYPE_PREFIX = "GeneratedRandomParent"
internal const val GENERATED_SOMETIMES_PASSIVE_PARENT_FIELD = "value1"
internal const val GENERATED_ROOT_REFERENCE_NAMESPACE_FIELD = "generatedRootReferences"
internal const val GENERATED_ROOT_REFERENCE_NAMESPACE_1 = "GeneratedRootReferenceNamespace1"
internal const val GENERATED_ROOT_REFERENCE_NAMESPACE_2 = "GeneratedRootReferenceNamespace2"
internal const val GENERATED_ROOT_REFERENCE_NAMESPACE_3 = "GeneratedRootReferenceNamespace3"
internal const val GENERATED_ROOT_REFERENCE_CONSUMER_FIELD = "generatedRootReferenceConsumer"
internal const val GENERATED_ROOT_REFERENCE_CONSUMER = "GeneratedRootReferenceConsumer"
internal const val GENERATED_ROOT_REFERENCE_INTERFACE = "GeneratedRootReferenceEntity"
internal const val GENERATED_ROOT_REFERENCE_UNION = "GeneratedRootReferenceResult"
internal const val GENERATED_ROOT_REFERENCE_ENUM = "GeneratedRootReferenceMode"
internal const val GENERATED_ROOT_REFERENCE_INPUT = "GeneratedRootReferenceInput"
internal const val GENERATED_ROOT_REFERENCE_NESTED_INPUT = "GeneratedRootReferenceNestedInput"
internal const val GENERATED_ROOT_REFERENCE_PAIR_A = "GeneratedRootReferencePairA"
internal const val GENERATED_ROOT_REFERENCE_PAIR_B = "GeneratedRootReferencePairB"
internal const val GENERATED_ROOT_REFERENCE_QUAD_A = "GeneratedRootReferenceQuadA"
internal const val GENERATED_ROOT_REFERENCE_QUAD_B = "GeneratedRootReferenceQuadB"
internal const val GENERATED_ROOT_REFERENCE_QUAD_C = "GeneratedRootReferenceQuadC"
internal const val GENERATED_ROOT_REFERENCE_QUAD_D = "GeneratedRootReferenceQuadD"
internal const val GENERATED_ROOT_REFERENCE_EXTENSION_FIELD = "generatedExtension"
internal const val GENERATED_ROOT_REFERENCE_NODE_INTERFACE = "GeneratedRootReferenceNodeEntity"
internal const val GENERATED_ROOT_REFERENCE_NODE = "GeneratedRootReferenceNode"
internal const val GENERATED_ROOT_REFERENCE_NODE_FIELD = "generatedRootReferenceNode"

internal data class RootFieldReferenceTargetSpec(
    val path: List<FieldCoordinate>,
    val arguments: Map<String, Any?>,
)

internal data class RootFieldReferenceFamily(
    val targets: Map<String, RootFieldReferenceTargetSpec>,
    val targetCoordinates: Set<FieldCoordinate>,
    val namespaceCoordinates: Set<FieldCoordinate>,
    val consumerCoordinate: FieldCoordinate,
    val consumerValueCoordinates: Set<FieldCoordinate>,
    val fallbackCoordinate: FieldCoordinate,
    val extensionCoordinates: Set<FieldCoordinate>,
    val nodeResolverType: String? = null,
    val nodeConsumerCoordinate: FieldCoordinate? = null,
)

class ArbitrarySchema internal constructor(
    val sdl: String,
    internal val objects: List<ObjectDefinition>,
    internal val query: ObjectDefinition,
    internal val hashType: ObjectDefinition,
    internal val interfaces: List<InterfaceDefinitionSpec>,
    internal val unions: List<UnionDefinitionSpec>,
    internal val enums: List<EnumDefinitionSpec> = emptyList(),
    internal val inputObjects: List<InputObjectDefinitionSpec>,
    internal val deepFields: Map<String, String>,
    internal val rootFieldReferenceFamily: RootFieldReferenceFamily? = null,
    val features: SchemaFeatures,
) {
    val domainObjectTypeNames: Set<String> =
        objects.mapTo(linkedSetOf(), ObjectDefinition::name)

    /** User-domain fields eligible to be active or passive, excluding generated hash fields. */
    val sourceFieldCoordinates: Set<FieldCoordinate> =
        (listOf(query) + objects)
            .flatMap(ObjectDefinition::fields)
            .filterNot { field -> field.isGeneratedHashField() || field.isParentField }
            .mapTo(linkedSetOf(), FieldDefinitionSpec::coordinate)

    /** User-domain fields by non-Query object type, excluding generated hash fields. */
    val sourceFieldCoordinatesByObject: Map<String, Set<FieldCoordinate>> =
        objects.associate { objectType ->
            objectType.name to
                objectType.fields
                    .filterNot { field -> field.isGeneratedHashField() || field.isParentField }
                    .mapTo(linkedSetOf(), FieldDefinitionSpec::coordinate)
        }

    internal val allObjects: List<ObjectDefinition>
        get() = listOf(query) + objects + hashType

    internal fun objectNamed(name: String): ObjectDefinition =
        allObjects.single { it.name == name }

    internal fun possibleObjects(typeName: String): List<ObjectDefinition> =
        allObjects.singleOrNull { it.name == typeName }?.let(::listOf)
            ?: interfaces.singleOrNull { it.name == typeName }?.members
                ?.map(::objectNamed)
            ?: unions.singleOrNull { it.name == typeName }?.members
                ?.map(::objectNamed)
            ?: error("Unknown output type $typeName")

    internal fun fieldsOn(typeName: String): List<FieldDefinitionSpec> =
        allObjects.singleOrNull { it.name == typeName }?.fields
            ?: interfaces.singleOrNull { it.name == typeName }?.fields
            ?: emptyList()

    internal fun isComposite(typeName: String): Boolean =
        allObjects.any { it.name == typeName } ||
            interfaces.any { it.name == typeName } ||
            unions.any { it.name == typeName }

    internal fun enumNamed(typeName: String): EnumDefinitionSpec? =
        enums.singleOrNull { it.name == typeName }

    override fun toString(): String = sdl
}

data class SchemaFeatures(
    val hasArguments: Boolean,
    val hasMultipleArgumentField: Boolean,
    val hasScalarArguments: Boolean,
    val hasListArguments: Boolean,
    val hasInputObjectArguments: Boolean,
    val hasInputObjectListArguments: Boolean,
    val hasInputObjects: Boolean,
    val hasRecursiveInputTypes: Boolean,
    val hasOutputLists: Boolean,
    val hasRecursiveOutputEdges: Boolean,
    val hasNullableRecursiveOutputEdges: Boolean,
    val hasListRecursiveOutputEdges: Boolean,
    val hasImplementationArgumentDefaults: Boolean,
    val hasInterfaces: Boolean,
    val hasUnions: Boolean,
    val maximumParentChainDepth: Int,
    val randomParentFieldCount: Int,
    val randomParentListProducerCount: Int,
    val randomParentAbstractTargetCount: Int,
)

internal data class ObjectDefinition(
    val name: String,
    val implementsNode: Boolean,
    val interfaces: Set<String>,
    val fields: List<FieldDefinitionSpec>,
)

internal data class InterfaceDefinitionSpec(
    val name: String,
    val members: Set<String>,
    val fields: List<FieldDefinitionSpec>,
)

internal data class UnionDefinitionSpec(
    val name: String,
    val members: Set<String>,
)

internal data class EnumDefinitionSpec(
    val name: String,
    val values: List<String>,
)

private data class RandomParentGraph(
    val objects: List<ObjectDefinition> = emptyList(),
    val queryFields: List<FieldDefinitionSpec> = emptyList(),
    val unions: List<UnionDefinitionSpec> = emptyList(),
    val maximumChainDepth: Int = 0,
    val listProducerCount: Int = 0,
    val abstractTargetCount: Int = 0,
)

private data class RootFieldReferenceSchemaGraph(
    val objects: List<ObjectDefinition> = emptyList(),
    val queryFields: List<FieldDefinitionSpec> = emptyList(),
    val interfaces: List<InterfaceDefinitionSpec> = emptyList(),
    val unions: List<UnionDefinitionSpec> = emptyList(),
    val enums: List<EnumDefinitionSpec> = emptyList(),
    val inputObjects: List<InputObjectDefinitionSpec> = emptyList(),
    val family: RootFieldReferenceFamily? = null,
)

internal data class FieldDefinitionSpec(
    val ownerName: String,
    val name: String,
    val type: OutputTypeSpec,
    val arguments: List<ArgumentDefinitionSpec>,
    val isParentField: Boolean = false,
) {
    val coordinate: FieldCoordinate
        get() = FieldCoordinate(ownerName, name)
}

data class FieldCoordinate(
    val typeName: String,
    val fieldName: String,
) {
    override fun toString(): String = "$typeName/$fieldName"
}

fun FieldCoordinate.isGeneratedRandomParentField(): Boolean =
    typeName.startsWith(GENERATED_RANDOM_PARENT_TYPE_PREFIX) &&
        fieldName == GENERATED_PARENT_FIELD

internal data class ArgumentDefinitionSpec(
    val name: String,
    val type: InputTypeSpec,
    val defaultValue: graphql.language.Value<*>? = null,
)

internal fun FieldDefinitionSpec.isGeneratedHashField(): Boolean =
    name == GENERATED_HASH_FIELD && type.namedType == GENERATED_HASH_TYPE

internal data class InputObjectDefinitionSpec(
    val name: String,
    val fields: List<InputFieldDefinitionSpec>,
)

internal data class InputFieldDefinitionSpec(
    val name: String,
    val type: InputTypeSpec,
)

internal sealed interface InputTypeSpec {
    val nullable: Boolean
}

internal data class ScalarInputTypeSpec(
    val scalar: ScalarKind,
    override val nullable: Boolean,
) : InputTypeSpec

internal data class ListInputTypeSpec(
    val element: InputTypeSpec,
    override val nullable: Boolean,
) : InputTypeSpec

internal data class InputObjectInputTypeSpec(
    val name: String,
    override val nullable: Boolean,
) : InputTypeSpec

internal data class OutputTypeSpec(
    val namedType: String,
    val nullable: Boolean,
    val list: Boolean,
    val elementNullable: Boolean,
    val nestedElementNullabilities: List<Boolean> = emptyList(),
) {
    init {
        require(list || nestedElementNullabilities.isEmpty())
    }

    val listDepth: Int
        get() = if (list) nestedElementNullabilities.size + 1 else 0

    fun elementType(): OutputTypeSpec {
        require(list)
        return if (nestedElementNullabilities.isEmpty()) {
            copy(
                nullable = elementNullable,
                list = false,
                nestedElementNullabilities = emptyList(),
            )
        } else {
            copy(
                nullable = elementNullable,
                elementNullable = nestedElementNullabilities.first(),
                nestedElementNullabilities = nestedElementNullabilities.drop(1),
            )
        }
    }
}

internal enum class ScalarKind(
    val graphQLName: String,
) {
    BOOLEAN("Boolean"),
    FLOAT("Float"),
    ID("ID"),
    INT("Int"),
    STRING("String"),
}

fun Arb.Companion.schema(config: Config = Config.default): Arb<ArbitrarySchema> =
    arbitrary { random ->
        SchemaGenerator(config, random).generate()
    }

private class SchemaGenerator(
    private val config: Config,
    private val random: RandomSource,
) {
    fun generate(): ArbitrarySchema {
        val minimumDepth = config[MinimumSelectionDepth]
        require(minimumDepth <= config[MaxSelectionDepth]) {
            "Minimum selection depth cannot exceed maximum selection depth"
        }
        require(minimumDepth <= config[SchemaObjectCount].last) {
            "Schema object count must permit the minimum selection depth"
        }
        require(!config[RandomParentFieldsEnabled] || config[ParentFieldsEnabled]) {
            "Random parent fields require parent fields to be enabled"
        }
        val objectCountRange =
            maxOf(config[SchemaObjectCount].first, minimumDepth)..config[SchemaObjectCount].last
        val objectCount = Arb.int(objectCountRange).next(random)
        val objectNames = (0 until objectCount).map { "Object$it" }
        val rootFieldReferenceGraph = rootFieldReferenceGraph()
        val inputObjects = inputObjects() + rootFieldReferenceGraph.inputObjects
        val nodeNames =
            (
                if (config[InterfacesEnabled] && config[NodeResolversEnabled]) {
                    objectNames.filter { chance(config[NodeObjectWeight]) }
                } else {
                    emptyList()
                }
            ).toSet() +
                rootFieldReferenceGraph.objects
                    .filter(ObjectDefinition::implementsNode)
                    .map(ObjectDefinition::name)
        val baseObjects =
            objectNames.mapIndexed { index, name ->
                val laterObjects =
                    objectNames.drop(index + 1) +
                        rootFieldReferenceGraph.objects.map(ObjectDefinition::name)
                val recursiveObjects =
                    if (config[RecursiveOutputEdgesEnabled]) {
                        objectNames.take(index + 1)
                    } else {
                        emptyList()
                    }
                val fieldCount = Arb.int(config[ObjectFieldCount]).next(random)
                val generatedFields =
                    (0 until fieldCount).map { fieldIndex ->
                        field(
                            ownerName = name,
                            name = "field$fieldIndex",
                            objectTargets = laterObjects,
                            recursiveObjectTargets = recursiveObjects,
                            inputObjectNames = inputObjects.map(InputObjectDefinitionSpec::name),
                        )
                    }
                val fields =
                    if (index < minimumDepth - 1) {
                        listOf(deepField(name, objectNames[index + 1])) +
                            generatedFields.drop(1)
                    } else {
                        generatedFields
                    }
                ObjectDefinition(
                    name = name,
                    implementsNode = name in nodeNames,
                    interfaces = emptySet(),
                    fields = fields,
                )
            }
        val nonNodeObjectNames = objectNames.filterNot(nodeNames::contains)
        val generatedInterface =
            if (config[InterfacesEnabled] && nonNodeObjectNames.isNotEmpty()) {
                val members = nonEmptySubset(nonNodeObjectNames)
                val commonField =
                    FieldDefinitionSpec(
                        ownerName = "GeneratedInterface",
                        name = "common",
                        type =
                            OutputTypeSpec(
                                namedType = "String",
                                nullable = chance(config[NullableTypeWeight]),
                                list = false,
                                elementNullable = true,
                            ),
                        arguments = emptyList(),
                    )
                InterfaceDefinitionSpec(
                    name = "GeneratedInterface",
                    members = members,
                    fields = listOf(commonField),
                )
            } else {
                null
            }
        val domainObjects =
            baseObjects.map { objectType ->
                if (objectType.name !in generatedInterface?.members.orEmpty()) {
                    objectType
                } else {
                    objectType.copy(
                        interfaces = objectType.interfaces + generatedInterface!!.name,
                        fields =
                            objectType.fields +
                                generatedInterface.fields.map { field ->
                                    field.copy(
                                        ownerName = objectType.name,
                                        arguments =
                                            field.arguments +
                                                implementationArgumentDefault(),
                                    )
                                },
                    )
                }
            }
        val hashType =
            ObjectDefinition(
                name = GENERATED_HASH_TYPE,
                implementsNode = false,
                interfaces = emptySet(),
                fields =
                    listOf(
                        FieldDefinitionSpec(
                            ownerName = GENERATED_HASH_TYPE,
                            name = GENERATED_HASH_NESTED_FIELD,
                            type =
                                OutputTypeSpec(
                                    namedType = GENERATED_HASH_TYPE,
                                    nullable = true,
                                    list = false,
                                    elementNullable = false,
                                ),
                            arguments = emptyList(),
                        ),
                        FieldDefinitionSpec(
                            ownerName = GENERATED_HASH_TYPE,
                            name = GENERATED_HASH_FIELD,
                            type =
                                OutputTypeSpec(
                                    namedType = "Int",
                                    nullable = false,
                                    list = false,
                                    elementNullable = false,
                                ),
                            arguments = emptyList(),
                        ),
                    ),
            )
        val generatedUnion =
            if (config[UnionsEnabled] && nonNodeObjectNames.isNotEmpty()) {
                UnionDefinitionSpec(
                    name = "GeneratedUnion",
                    members = nonEmptySubset(nonNodeObjectNames),
                )
            } else {
                null
            }
        val ordinaryObjects =
            domainObjects.withAbstractOutputTargets(
                interfaces = listOfNotNull(generatedInterface),
                unions = listOfNotNull(generatedUnion),
            )
        val parentObjects = if (config[ParentFieldsEnabled]) parentObjects() else emptyList()
        val randomParentGraph =
            randomParentGraph(
                alternativeParentTypeNames = ordinaryObjects.map(ObjectDefinition::name),
                inputObjectNames = inputObjects.map(InputObjectDefinitionSpec::name),
            )
        val objects =
            (
                ordinaryObjects + parentObjects + randomParentGraph.objects +
                    rootFieldReferenceGraph.objects
            ).map { objectType ->
                objectType.copy(fields = objectType.fields + generatedHashField(objectType.name))
            }
        val interfaces =
            buildList {
                if (nodeNames.isNotEmpty()) {
                    add(
                        InterfaceDefinitionSpec(
                            name = "Node",
                            members = nodeNames,
                            fields =
                                listOf(
                                    FieldDefinitionSpec(
                                        ownerName = "Node",
                                        name = "id",
                                        type =
                                            OutputTypeSpec(
                                                namedType = "ID",
                                                nullable = false,
                                                list = false,
                                                elementNullable = false,
                                            ),
                                        arguments = emptyList(),
                                    ),
                                ),
                        ),
                    )
                }
                generatedInterface?.let(::add)
                addAll(rootFieldReferenceGraph.interfaces)
            }
        val unions =
            listOfNotNull(generatedUnion) + randomParentGraph.unions +
                rootFieldReferenceGraph.unions
        val enums = rootFieldReferenceGraph.enums
        val queryTargets =
            objectNames + rootFieldReferenceGraph.objects.map(ObjectDefinition::name) +
                interfaces.map(InterfaceDefinitionSpec::name) +
                listOfNotNull(generatedUnion).map(UnionDefinitionSpec::name) +
                rootFieldReferenceGraph.unions.map(UnionDefinitionSpec::name)
        val queryFieldCount = Arb.int(config[QueryFieldCount]).next(random)
        val generatedQueryFields =
            (0 until queryFieldCount).map { index ->
                field(
                    ownerName = "Query",
                    name = "query$index",
                    objectTargets = queryTargets,
                    recursiveObjectTargets = emptyList(),
                    inputObjectNames = inputObjects.map(InputObjectDefinitionSpec::name),
                    preferObject = true,
                    forceScalar =
                        config[QueryScalarFieldWeight] > 0.0 &&
                            chance(config[QueryScalarFieldWeight]),
                )
            }
        val query =
            ObjectDefinition(
                name = "Query",
                implementsNode = false,
                interfaces = emptySet(),
                fields =
                    randomParentGraph.queryFields +
                        rootFieldReferenceGraph.queryFields +
                        parentRootField() +
                        if (minimumDepth > 0) {
                        listOf(deepField("Query", objectNames.first(), "query0")) +
                            generatedQueryFields.drop(1)
                    } else {
                        generatedQueryFields
                    },
            )
        val definitions =
            buildList {
                addAll(inputObjects.map(::inputObjectType))
                addAll(interfaces.map(::interfaceType))
                add(objectType(hashType))
                addAll(objects.map(::objectType))
                addAll(unions.map(::unionType))
                addAll(enums.map(::enumType))
                add(objectType(query))
            }
        val sdl =
            (if (config[ParentFieldsEnabled]) "directive @parent on FIELD_DEFINITION\n\n" else "") +
                AstPrinter.printAst(
                    Document.newDocument().definitions(definitions).build(),
                ).trim()
        val deepFields =
            buildMap<String, String> {
                if (config[ParentFieldsEnabled]) {
                    put("Query", GENERATED_PARENT_ROOT_FIELD)
                    put(GENERATED_PARENT_ROOT_TYPE, GENERATED_PARENT_CHILD_FIELD)
                    put(GENERATED_PARENT_CHILD_TYPE, GENERATED_PARENT_CHILD_FIELD)
                    put(GENERATED_PARENT_GRANDCHILD_TYPE, GENERATED_PARENT_CHILD_FIELD)
                } else {
                    if (minimumDepth > 0) put("Query", "query0")
                    (0 until minimumDepth - 1).forEach { index ->
                        put(objectNames[index], "field0")
                    }
                }
            }
        val features =
            schemaFeatures(
                objects = objects,
                query = query,
                interfaces = interfaces,
                unions = unions,
                inputObjects = inputObjects,
                randomParentGraph = randomParentGraph,
            )
        return ArbitrarySchema(
            sdl = sdl,
            objects = objects,
            query = query,
            hashType = hashType,
            interfaces = interfaces,
            unions = unions,
            enums = enums,
            inputObjects = inputObjects,
            deepFields = deepFields,
            rootFieldReferenceFamily = rootFieldReferenceGraph.family,
            features = features,
        )
    }

    private fun rootFieldReferenceGraph(): RootFieldReferenceSchemaGraph {
        if (!config[RootFieldReferencesEnabled]) return RootFieldReferenceSchemaGraph()

        fun output(
            name: String,
            nullable: Boolean = false,
        ): OutputTypeSpec =
            OutputTypeSpec(
                namedType = name,
                nullable = nullable,
                list = false,
                elementNullable = false,
            )

        fun field(
            owner: String,
            name: String,
            type: OutputTypeSpec,
            arguments: List<ArgumentDefinitionSpec> = emptyList(),
        ): FieldDefinitionSpec =
            FieldDefinitionSpec(
                ownerName = owner,
                name = name,
                type = type,
                arguments = arguments,
            )

        fun scalarArgument(name: String, scalar: ScalarKind): ArgumentDefinitionSpec =
            ArgumentDefinitionSpec(name, ScalarInputTypeSpec(scalar, nullable = false))

        val nestedInput =
            InputObjectDefinitionSpec(
                name = GENERATED_ROOT_REFERENCE_NESTED_INPUT,
                fields =
                    listOf(
                        InputFieldDefinitionSpec(
                            "text",
                            ScalarInputTypeSpec(ScalarKind.STRING, nullable = false),
                        ),
                        InputFieldDefinitionSpec(
                            "flag",
                            ScalarInputTypeSpec(ScalarKind.BOOLEAN, nullable = false),
                        ),
                    ),
            )
        val input =
            InputObjectDefinitionSpec(
                name = GENERATED_ROOT_REFERENCE_INPUT,
                fields =
                    listOf(
                        InputFieldDefinitionSpec(
                            "number",
                            ScalarInputTypeSpec(ScalarKind.INT, nullable = false),
                        ),
                        InputFieldDefinitionSpec(
                            "nested",
                            InputObjectInputTypeSpec(
                                GENERATED_ROOT_REFERENCE_NESTED_INPUT,
                                nullable = false,
                            ),
                        ),
                    ),
            )

        val extensionCoordinates = linkedSetOf<FieldCoordinate>()
        fun resultObject(
            name: String,
            interfaces: Set<String> = emptySet(),
            recursiveField: FieldDefinitionSpec,
        ): ObjectDefinition {
            val extension =
                field(
                    name,
                    GENERATED_ROOT_REFERENCE_EXTENSION_FIELD,
                    output("String"),
                )
            extensionCoordinates += extension.coordinate
            return ObjectDefinition(
                name = name,
                implementsNode = false,
                interfaces = interfaces,
                fields =
                    listOf(
                        field(name, "label", output("String")),
                        recursiveField,
                        extension,
                    ),
            )
        }

        val resultObjects =
            listOf(
                resultObject(
                    GENERATED_ROOT_REFERENCE_PAIR_A,
                    interfaces = setOf(GENERATED_ROOT_REFERENCE_INTERFACE),
                    recursiveField =
                        field(
                            GENERATED_ROOT_REFERENCE_PAIR_A,
                            "pair",
                            output(GENERATED_ROOT_REFERENCE_PAIR_B, nullable = true),
                        ),
                ),
                resultObject(
                    GENERATED_ROOT_REFERENCE_PAIR_B,
                    recursiveField =
                        field(
                            GENERATED_ROOT_REFERENCE_PAIR_B,
                            "pair",
                            output(GENERATED_ROOT_REFERENCE_PAIR_A, nullable = true),
                        ),
                ),
                resultObject(
                    GENERATED_ROOT_REFERENCE_QUAD_A,
                    interfaces = setOf(GENERATED_ROOT_REFERENCE_INTERFACE),
                    recursiveField =
                        field(
                            GENERATED_ROOT_REFERENCE_QUAD_A,
                            "next",
                            output(GENERATED_ROOT_REFERENCE_QUAD_B, nullable = true),
                        ),
                ),
                resultObject(
                    GENERATED_ROOT_REFERENCE_QUAD_B,
                    recursiveField =
                        field(
                            GENERATED_ROOT_REFERENCE_QUAD_B,
                            "next",
                            output(GENERATED_ROOT_REFERENCE_QUAD_C, nullable = true),
                        ),
                ),
                resultObject(
                    GENERATED_ROOT_REFERENCE_QUAD_C,
                    recursiveField =
                        field(
                            GENERATED_ROOT_REFERENCE_QUAD_C,
                            "next",
                            output(GENERATED_ROOT_REFERENCE_QUAD_D, nullable = true),
                        ),
                ),
                resultObject(
                    GENERATED_ROOT_REFERENCE_QUAD_D,
                    recursiveField =
                        field(
                            GENERATED_ROOT_REFERENCE_QUAD_D,
                            "next",
                            output(GENERATED_ROOT_REFERENCE_QUAD_A, nullable = true),
                        ),
                ),
            )

        val nodeObject =
            if (config[NodeResolversEnabled]) {
                val extension =
                    field(
                        GENERATED_ROOT_REFERENCE_NODE,
                        GENERATED_ROOT_REFERENCE_EXTENSION_FIELD,
                        output("String"),
                    )
                extensionCoordinates += extension.coordinate
                ObjectDefinition(
                    name = GENERATED_ROOT_REFERENCE_NODE,
                    implementsNode = true,
                    interfaces = setOf(GENERATED_ROOT_REFERENCE_NODE_INTERFACE),
                    fields = listOf(extension),
                )
            } else {
                null
            }

        val namespaceQuery =
            field(
                "Query",
                GENERATED_ROOT_REFERENCE_NAMESPACE_FIELD,
                output(GENERATED_ROOT_REFERENCE_NAMESPACE_1),
            )
        val level2 =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_1,
                "level2",
                output(GENERATED_ROOT_REFERENCE_NAMESPACE_2),
            )
        val level3 =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_2,
                "level3",
                output(GENERATED_ROOT_REFERENCE_NAMESPACE_3),
            )
        val zero =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_1,
                "zero",
                output(GENERATED_ROOT_REFERENCE_INTERFACE),
            )
        val objectValue =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_1,
                "objectValue",
                output(GENERATED_ROOT_REFERENCE_PAIR_A),
            )
        val one =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_2,
                "one",
                output(GENERATED_ROOT_REFERENCE_INTERFACE),
                listOf(scalarArgument("id", ScalarKind.INT)),
            )
        val tail =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_3,
                "tail",
                output(GENERATED_ROOT_REFERENCE_INTERFACE),
            )
        val four =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_3,
                "four",
                output(GENERATED_ROOT_REFERENCE_UNION),
                listOf(
                    scalarArgument("number", ScalarKind.INT),
                    scalarArgument("text", ScalarKind.STRING),
                    ArgumentDefinitionSpec(
                        "input",
                        InputObjectInputTypeSpec(GENERATED_ROOT_REFERENCE_INPUT, nullable = false),
                    ),
                    ArgumentDefinitionSpec(
                        "nested",
                        InputObjectInputTypeSpec(
                            GENERATED_ROOT_REFERENCE_NESTED_INPUT,
                            nullable = false,
                        ),
                    ),
                ),
            )
        val enumValue =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_3,
                "enumValue",
                output(GENERATED_ROOT_REFERENCE_ENUM),
            )
        val scalarValue =
            field(
                GENERATED_ROOT_REFERENCE_NAMESPACE_3,
                "scalarValue",
                output("Int"),
                listOf(scalarArgument("value", ScalarKind.INT)),
            )
        val nodeValue =
            nodeObject?.let {
                field(
                    GENERATED_ROOT_REFERENCE_NAMESPACE_1,
                    "nodeValue",
                    output(GENERATED_ROOT_REFERENCE_NODE_INTERFACE),
                )
            }

        val consumer =
            field(
                "Query",
                GENERATED_ROOT_REFERENCE_CONSUMER_FIELD,
                output(GENERATED_ROOT_REFERENCE_CONSUMER),
            )
        val nodeConsumer =
            nodeObject?.let {
                field(
                    "Query",
                    GENERATED_ROOT_REFERENCE_NODE_FIELD,
                    output(GENERATED_ROOT_REFERENCE_NODE),
                )
            }
        val consumerFields =
            listOf(
                field(
                    GENERATED_ROOT_REFERENCE_CONSUMER,
                    "objectValue",
                    output(GENERATED_ROOT_REFERENCE_INTERFACE),
                ),
                field(
                    GENERATED_ROOT_REFERENCE_CONSUMER,
                    "concreteValue",
                    output(GENERATED_ROOT_REFERENCE_PAIR_A),
                ),
                field(
                    GENERATED_ROOT_REFERENCE_CONSUMER,
                    "unionValue",
                    output(GENERATED_ROOT_REFERENCE_UNION),
                ),
                field(
                    GENERATED_ROOT_REFERENCE_CONSUMER,
                    "enumValue",
                    output(GENERATED_ROOT_REFERENCE_ENUM),
                ),
                field(
                    GENERATED_ROOT_REFERENCE_CONSUMER,
                    "scalarValue",
                    output("Int"),
                ),
                field(
                    GENERATED_ROOT_REFERENCE_CONSUMER,
                    "listValue",
                    OutputTypeSpec(
                        namedType = GENERATED_ROOT_REFERENCE_INTERFACE,
                        nullable = false,
                        list = true,
                        elementNullable = false,
                    ),
                ),
                field(
                    GENERATED_ROOT_REFERENCE_CONSUMER,
                    "activeFallback",
                    output("String"),
                ),
            )

        val namespaceObjects =
            listOf(
                ObjectDefinition(
                    GENERATED_ROOT_REFERENCE_NAMESPACE_1,
                    implementsNode = false,
                    interfaces = emptySet(),
                    fields = listOf(level2, zero, objectValue) + listOfNotNull(nodeValue),
                ),
                ObjectDefinition(
                    GENERATED_ROOT_REFERENCE_NAMESPACE_2,
                    implementsNode = false,
                    interfaces = emptySet(),
                    fields = listOf(level3, one),
                ),
                ObjectDefinition(
                    GENERATED_ROOT_REFERENCE_NAMESPACE_3,
                    implementsNode = false,
                    interfaces = emptySet(),
                    fields = listOf(tail, four, enumValue, scalarValue),
                ),
            )
        val consumerObject =
            ObjectDefinition(
                GENERATED_ROOT_REFERENCE_CONSUMER,
                implementsNode = false,
                interfaces = emptySet(),
                fields = consumerFields,
            )

        val nestedArguments =
            mapOf("text" to "nested", "flag" to true)
        val targets =
            linkedMapOf(
                "zero" to
                    RootFieldReferenceTargetSpec(
                        path = listOf(namespaceQuery.coordinate, zero.coordinate),
                        arguments = emptyMap(),
                    ),
                "object" to
                    RootFieldReferenceTargetSpec(
                        path = listOf(namespaceQuery.coordinate, objectValue.coordinate),
                        arguments = emptyMap(),
                    ),
                "one" to
                    RootFieldReferenceTargetSpec(
                        path = listOf(namespaceQuery.coordinate, level2.coordinate, one.coordinate),
                        arguments = mapOf("id" to 7),
                    ),
                "tail" to
                    RootFieldReferenceTargetSpec(
                        path =
                            listOf(
                                namespaceQuery.coordinate,
                                level2.coordinate,
                                level3.coordinate,
                                tail.coordinate,
                            ),
                        arguments = emptyMap(),
                    ),
                "four" to
                    RootFieldReferenceTargetSpec(
                        path =
                            listOf(
                                namespaceQuery.coordinate,
                                level2.coordinate,
                                level3.coordinate,
                                four.coordinate,
                            ),
                        arguments =
                            mapOf(
                                "number" to 11,
                                "text" to "four",
                                "input" to mapOf("number" to 13, "nested" to nestedArguments),
                                "nested" to nestedArguments,
                            ),
                    ),
                "enum" to
                    RootFieldReferenceTargetSpec(
                        path =
                            listOf(
                                namespaceQuery.coordinate,
                                level2.coordinate,
                                level3.coordinate,
                                enumValue.coordinate,
                            ),
                        arguments = emptyMap(),
                    ),
                "scalar" to
                    RootFieldReferenceTargetSpec(
                        path =
                            listOf(
                                namespaceQuery.coordinate,
                                level2.coordinate,
                                level3.coordinate,
                                scalarValue.coordinate,
                            ),
                        arguments = mapOf("value" to 17),
                    ),
            ).apply {
                nodeValue?.let { target ->
                    put(
                        "node",
                        RootFieldReferenceTargetSpec(
                            path = listOf(namespaceQuery.coordinate, target.coordinate),
                            arguments = emptyMap(),
                        ),
                    )
                }
            }
        return RootFieldReferenceSchemaGraph(
            objects = namespaceObjects + resultObjects + consumerObject + listOfNotNull(nodeObject),
            queryFields = listOf(namespaceQuery, consumer) + listOfNotNull(nodeConsumer),
            interfaces =
                listOf(
                    InterfaceDefinitionSpec(
                        name = GENERATED_ROOT_REFERENCE_INTERFACE,
                        members =
                            setOf(
                                GENERATED_ROOT_REFERENCE_PAIR_A,
                                GENERATED_ROOT_REFERENCE_QUAD_A,
                            ),
                        fields =
                            listOf(
                                field(
                                    GENERATED_ROOT_REFERENCE_INTERFACE,
                                    "label",
                                    output("String"),
                                ),
                            ),
                    ),
                ) +
                    listOfNotNull(
                        nodeObject?.let {
                            InterfaceDefinitionSpec(
                                name = GENERATED_ROOT_REFERENCE_NODE_INTERFACE,
                                members = setOf(GENERATED_ROOT_REFERENCE_NODE),
                                fields =
                                    listOf(
                                        field(
                                            GENERATED_ROOT_REFERENCE_NODE_INTERFACE,
                                            GENERATED_ROOT_REFERENCE_EXTENSION_FIELD,
                                            output("String"),
                                        ),
                                    ),
                            )
                        },
                    ),
            unions =
                listOf(
                    UnionDefinitionSpec(
                        GENERATED_ROOT_REFERENCE_UNION,
                        setOf(
                            GENERATED_ROOT_REFERENCE_PAIR_B,
                            GENERATED_ROOT_REFERENCE_QUAD_D,
                        ),
                    ),
                ),
            enums =
                listOf(
                    EnumDefinitionSpec(
                        GENERATED_ROOT_REFERENCE_ENUM,
                        listOf("FIRST", "SECOND", "THIRD"),
                    ),
                ),
            inputObjects = listOf(input, nestedInput),
            family =
                RootFieldReferenceFamily(
                    targets = targets,
                    targetCoordinates = targets.values.mapTo(linkedSetOf()) { it.path.last() },
                    namespaceCoordinates =
                        setOf(namespaceQuery.coordinate, level2.coordinate, level3.coordinate),
                    consumerCoordinate = consumer.coordinate,
                    consumerValueCoordinates =
                        consumerFields
                            .filterNot { it.name == "activeFallback" }
                            .mapTo(linkedSetOf(), FieldDefinitionSpec::coordinate),
                    fallbackCoordinate =
                        consumerFields.single { it.name == "activeFallback" }.coordinate,
                    extensionCoordinates = extensionCoordinates,
                    nodeResolverType = nodeObject?.name,
                    nodeConsumerCoordinate = nodeConsumer?.coordinate,
                ),
        )
    }

    private fun generatedHashField(ownerName: String): FieldDefinitionSpec =
        FieldDefinitionSpec(
            ownerName = ownerName,
            name = GENERATED_HASH_FIELD,
            type =
                OutputTypeSpec(
                    namedType = GENERATED_HASH_TYPE,
                    nullable = false,
                    list = false,
                    elementNullable = false,
                ),
            arguments = emptyList(),
        )

    private fun parentRootField(): List<FieldDefinitionSpec> =
        if (config[ParentFieldsEnabled]) {
            listOf(
                FieldDefinitionSpec(
                    ownerName = "Query",
                    name = GENERATED_PARENT_ROOT_FIELD,
                    type = objectOutputType(GENERATED_PARENT_ROOT_TYPE),
                    arguments = emptyList(),
                ),
            )
        } else {
            emptyList()
        }

    private fun parentObjects(): List<ObjectDefinition> {
        fun parentField(ownerName: String, parentType: String): FieldDefinitionSpec =
            FieldDefinitionSpec(
                ownerName = ownerName,
                name = GENERATED_PARENT_FIELD,
                type = objectOutputType(parentType),
                arguments = emptyList(),
                isParentField = true,
            )

        fun childField(ownerName: String, childType: String): FieldDefinitionSpec =
            FieldDefinitionSpec(
                ownerName = ownerName,
                name = GENERATED_PARENT_CHILD_FIELD,
                type = objectOutputType(childType),
                arguments = emptyList(),
            )

        fun scalarField(ownerName: String, name: String): FieldDefinitionSpec =
            FieldDefinitionSpec(
                ownerName = ownerName,
                name = name,
                type =
                    OutputTypeSpec(
                        namedType = "Int",
                        nullable = false,
                        list = false,
                        elementNullable = false,
                    ),
                arguments = emptyList(),
            )

        return listOf(
            ObjectDefinition(
                name = GENERATED_PARENT_ROOT_TYPE,
                implementsNode = false,
                interfaces = emptySet(),
                fields =
                    listOf(
                        childField(GENERATED_PARENT_ROOT_TYPE, GENERATED_PARENT_CHILD_TYPE),
                        scalarField(GENERATED_PARENT_ROOT_TYPE, GENERATED_PARENT_VALUE_FIELD),
                    ),
            ),
            ObjectDefinition(
                name = GENERATED_PARENT_CHILD_TYPE,
                implementsNode = false,
                interfaces = emptySet(),
                fields =
                    listOf(
                        parentField(GENERATED_PARENT_CHILD_TYPE, GENERATED_PARENT_ROOT_TYPE),
                        childField(
                            GENERATED_PARENT_CHILD_TYPE,
                            GENERATED_PARENT_GRANDCHILD_TYPE,
                        ),
                    ),
            ),
            ObjectDefinition(
                name = GENERATED_PARENT_GRANDCHILD_TYPE,
                implementsNode = false,
                interfaces = emptySet(),
                fields =
                    listOf(
                        parentField(
                            GENERATED_PARENT_GRANDCHILD_TYPE,
                            GENERATED_PARENT_CHILD_TYPE,
                        ),
                        childField(
                            GENERATED_PARENT_GRANDCHILD_TYPE,
                            GENERATED_PARENT_GREAT_GRANDCHILD_TYPE,
                        ),
                    ),
            ),
            ObjectDefinition(
                name = GENERATED_PARENT_GREAT_GRANDCHILD_TYPE,
                implementsNode = false,
                interfaces = emptySet(),
                fields =
                    listOf(
                        parentField(
                            GENERATED_PARENT_GREAT_GRANDCHILD_TYPE,
                            GENERATED_PARENT_GRANDCHILD_TYPE,
                        ),
                        scalarField(
                            GENERATED_PARENT_GREAT_GRANDCHILD_TYPE,
                            GENERATED_PARENT_RESULT_FIELD,
                        ),
                    ),
            ),
        )
    }

    private fun randomParentGraph(
        alternativeParentTypeNames: List<String>,
        inputObjectNames: List<String>,
    ): RandomParentGraph {
        if (!config[RandomParentFieldsEnabled]) return RandomParentGraph()

        val fieldsByObject = linkedMapOf<String, MutableList<FieldDefinitionSpec>>()
        val queryFields = mutableListOf<FieldDefinitionSpec>()
        val unions = mutableListOf<UnionDefinitionSpec>()
        var maximumChainDepth = 0
        var listProducerCount = 0
        var abstractTargetCount = 0
        val chainCount = 2

        repeat(chainCount) { chainIndex ->
            val chainDepth = Arb.int(3..4).next(random)
            maximumChainDepth = maxOf(maximumChainDepth, chainDepth)
            var parentOwner = "Query"

            repeat(chainDepth) { level ->
                val childName =
                    "$GENERATED_RANDOM_PARENT_TYPE_PREFIX${chainIndex}Level$level"
                val producerType = randomParentProducerType(childName)
                if (producerType.list) listProducerCount += 1
                val producer =
                    FieldDefinitionSpec(
                        ownerName = parentOwner,
                        name = "randomParent${chainIndex}Child$level",
                        type = producerType,
                        arguments = emptyList(),
                    )
                if (parentOwner == "Query") {
                    queryFields += producer
                } else {
                    fieldsByObject.getValue(parentOwner) += producer
                }

                val alternativeTargets =
                    (alternativeParentTypeNames + fieldsByObject.keys)
                        .filterNot { typeName -> typeName == parentOwner }
                val useAbstractTarget =
                    parentOwner != "Query" &&
                        alternativeTargets.isNotEmpty() &&
                        chance(0.4)
                val parentTarget =
                    if (useAbstractTarget) {
                        abstractTargetCount += 1
                        val unionName =
                            "$GENERATED_RANDOM_PARENT_TYPE_PREFIX${chainIndex}Parent$level"
                        val firstAlternative = Arb.element(alternativeTargets).next(random)
                        val remainingAlternatives =
                            alternativeTargets.filterNot { typeName ->
                                typeName == firstAlternative
                            }
                        val alternatives =
                            listOf(firstAlternative) +
                                remainingAlternatives
                                    .takeIf { candidates ->
                                        candidates.isNotEmpty() && chance(0.5)
                                    }?.let { candidates ->
                                        listOf(Arb.element(candidates).next(random))
                                    }.orEmpty()
                        unions +=
                            UnionDefinitionSpec(
                                name = unionName,
                                members = (listOf(parentOwner) + alternatives).toSet(),
                            )
                        unionName
                    } else {
                        parentOwner
                }

                val scalarFieldCount = Arb.int(2..4).next(random)
                val sharedArgumentType = inputType(inputObjectNames)
                fieldsByObject[childName] =
                    buildList {
                        add(
                            FieldDefinitionSpec(
                                ownerName = childName,
                                name = GENERATED_PARENT_FIELD,
                                type =
                                    objectOutputType(parentTarget).copy(
                                        nullable = chance(config[NullableTypeWeight]),
                                    ),
                                arguments = emptyList(),
                                isParentField = true,
                            ),
                        )
                        repeat(scalarFieldCount) { fieldIndex ->
                            val fieldName = "value$fieldIndex"
                            val argumentlessSometimesPassiveParentWitness =
                                config[SometimesPassiveFieldWeight] > 0.0 &&
                                    fieldName == GENERATED_SOMETIMES_PASSIVE_PARENT_FIELD
                            add(
                                FieldDefinitionSpec(
                                    ownerName = childName,
                                    name = fieldName,
                                    type =
                                        OutputTypeSpec(
                                            namedType =
                                                Arb.element(ScalarKind.entries)
                                                    .next(random)
                                                    .graphQLName,
                                            nullable = chance(config[NullableTypeWeight]),
                                            list = false,
                                            elementNullable = false,
                                        ),
                                    arguments =
                                        if (
                                            config[ArgumentsEnabled] &&
                                            !argumentlessSometimesPassiveParentWitness &&
                                            (
                                                fieldIndex == 0 ||
                                                    chance(config[FieldArgumentWeight])
                                            )
                                        ) {
                                            listOf(
                                                ArgumentDefinitionSpec(
                                                    name = "arg",
                                                    type = sharedArgumentType,
                                                ),
                                                ArgumentDefinitionSpec(
                                                    name = "arg1",
                                                    type = sharedArgumentType,
                                                ),
                                            )
                                        } else {
                                            emptyList()
                                        },
                                ),
                            )
                        }
                    }.toMutableList()
                parentOwner = childName
            }
        }

        return RandomParentGraph(
            objects =
                fieldsByObject.map { (typeName, fields) ->
                    ObjectDefinition(
                        name = typeName,
                        implementsNode = false,
                        interfaces = emptySet(),
                        fields = fields,
                    )
                },
            queryFields = queryFields,
            unions = unions,
            maximumChainDepth = maximumChainDepth,
            listProducerCount = listProducerCount,
            abstractTargetCount = abstractTargetCount,
        )
    }

    private fun randomParentProducerType(childName: String): OutputTypeSpec {
        val listDepth =
            if (config[ListsEnabled] && chance(0.5)) {
                Arb.int(1..config[MaxOutputListDepth]).next(random)
            } else {
                0
            }
        return OutputTypeSpec(
            namedType = childName,
            nullable = chance(config[NullableTypeWeight]),
            list = listDepth > 0,
            elementNullable = listDepth > 0 && chance(config[NullableTypeWeight]),
            nestedElementNullabilities =
                List((listDepth - 1).coerceAtLeast(0)) {
                    chance(config[NullableTypeWeight])
                },
        )
    }

    private fun objectOutputType(typeName: String): OutputTypeSpec =
        OutputTypeSpec(
            namedType = typeName,
            nullable = false,
            list = false,
            elementNullable = false,
        )

    private fun deepField(
        ownerName: String,
        targetName: String,
        fieldName: String = "field0",
    ): FieldDefinitionSpec =
        FieldDefinitionSpec(
            ownerName = ownerName,
            name = fieldName,
            type =
                OutputTypeSpec(
                    namedType = targetName,
                    nullable = false,
                    list = false,
                    elementNullable = false,
                ),
            arguments = emptyList(),
        )

    private fun field(
        ownerName: String,
        name: String,
        objectTargets: List<String>,
        recursiveObjectTargets: List<String>,
        inputObjectNames: List<String>,
        preferObject: Boolean = false,
        forceScalar: Boolean = false,
    ): FieldDefinitionSpec {
        val useRecursiveTarget =
            recursiveObjectTargets.isNotEmpty() &&
                chance(config[RecursiveOutputEdgeWeight])
        val availableObjectTargets =
            if (useRecursiveTarget) {
                recursiveObjectTargets
            } else {
                objectTargets
            }
        val useObject =
            !forceScalar &&
                availableObjectTargets.isNotEmpty() &&
                (preferObject || chance(config[ObjectOutputFieldWeight]))
        val namedType =
            if (useObject) {
                Arb.element(availableObjectTargets).next(random)
            } else {
                Arb.element(ScalarKind.entries).next(random).graphQLName
            }
        val isList = config[ListsEnabled] && chance(config[ListTypeWeight])
        // Every back edge can terminate as null or an empty list.
        val nullable =
            chance(config[NullableTypeWeight]) ||
                (useObject && useRecursiveTarget && !isList)
        val arguments =
            if (config[ArgumentsEnabled] && chance(config[FieldArgumentWeight])) {
                List(Arb.int(config[FieldArgumentCount]).next(random)) { index ->
                    ArgumentDefinitionSpec(
                        name = if (index == 0) "arg" else "arg$index",
                        type = inputType(inputObjectNames),
                    )
                }
            } else {
                emptyList()
            }
        val listDepth =
            if (isList) {
                generateSequence(1) { depth ->
                    (depth + 1).takeIf {
                        it <= config[MaxOutputListDepth] &&
                            chance(config[ListTypeWeight])
                    }
                }.last()
            } else {
                0
            }
        val elementNullable = chance(config[NullableTypeWeight])
        val nestedElementNullabilities =
            List((listDepth - 1).coerceAtLeast(0)) {
                chance(config[NullableTypeWeight])
            }
        return FieldDefinitionSpec(
            ownerName = ownerName,
            name = name,
            type =
                OutputTypeSpec(
                    namedType = namedType,
                    nullable = nullable,
                    list = isList,
                    elementNullable = elementNullable,
                    nestedElementNullabilities = nestedElementNullabilities,
                ),
            arguments = arguments,
        )
    }

    private fun implementationArgumentDefault(): List<ArgumentDefinitionSpec> =
        if (
            config[ArgumentsEnabled] &&
            chance(config[ImplementationArgumentDefaultWeight])
        ) {
            listOf(
                ArgumentDefinitionSpec(
                    name = "implementationDefault",
                    type = ScalarInputTypeSpec(ScalarKind.INT, nullable = true),
                    defaultValue =
                        IntValue
                            .newIntValue(BigInteger.valueOf(7))
                            .build(),
                ),
            )
        } else {
            emptyList()
        }

    private fun inputObjects(): List<InputObjectDefinitionSpec> {
        if (!config[ArgumentsEnabled] || !config[InputObjectsEnabled]) return emptyList()

        val inputObjectCount = Arb.int(config[InputObjectCount]).next(random)
        val names = (0 until inputObjectCount).map { "InputObject$it" }
        return names.mapIndexed { ownerIndex, name ->
            val fieldCount = Arb.int(config[InputObjectFieldCount]).next(random)
            InputObjectDefinitionSpec(
                name = name,
                fields =
                    (0 until fieldCount).map { fieldIndex ->
                        InputFieldDefinitionSpec(
                            name = "input$fieldIndex",
                            type =
                                inputType(
                                    inputObjectNames = names,
                                    ownerInputObjectIndex = ownerIndex,
                                ),
                        )
                    },
            )
        }
    }

    private fun inputType(
        inputObjectNames: List<String>,
        depth: Int = 0,
        ownerInputObjectIndex: Int? = null,
    ): InputTypeSpec {
        val canRecurse = depth < config[MaxInputTypeDepth]
        if (
            canRecurse &&
            config[ListsEnabled] &&
            chance(config[InputListTypeWeight])
        ) {
            val element =
                inputType(
                    inputObjectNames = inputObjectNames,
                    depth = depth + 1,
                    ownerInputObjectIndex = ownerInputObjectIndex,
                )
            val closesRequiredCycle =
                ownerInputObjectIndex != null &&
                    element
                        .referencedInputObjects()
                        .any { target ->
                            inputObjectNames.indexOf(target) <= ownerInputObjectIndex
                        }
            return ListInputTypeSpec(
                element = element,
                nullable =
                    closesRequiredCycle ||
                        chance(config[NullableTypeWeight]),
            )
        }

        if (
            canRecurse &&
            inputObjectNames.isNotEmpty() &&
            chance(config[InputObjectTypeWeight])
        ) {
            val candidates =
                if (
                    ownerInputObjectIndex != null &&
                    !config[RecursiveInputTypesEnabled]
                ) {
                    inputObjectNames.drop(ownerInputObjectIndex + 1)
                } else {
                    inputObjectNames
            }
            if (candidates.isNotEmpty()) {
                val target = Arb.element(candidates).next(random)
                val targetIndex = inputObjectNames.indexOf(target)
                val closesRequiredCycle =
                    ownerInputObjectIndex != null &&
                        targetIndex <= ownerInputObjectIndex
                return InputObjectInputTypeSpec(
                    name = target,
                    nullable =
                        closesRequiredCycle ||
                            chance(config[NullableTypeWeight]),
                )
            }
        }

        return ScalarInputTypeSpec(
            scalar = Arb.element(ScalarKind.entries).next(random),
            nullable = chance(config[NullableTypeWeight]),
        )
    }

    private fun interfaceType(definition: InterfaceDefinitionSpec): InterfaceTypeDefinition =
        InterfaceTypeDefinition
            .newInterfaceTypeDefinition()
            .name(definition.name)
            .definitions(definition.fields.map(::fieldDefinition))
            .build()

    private fun objectType(definition: ObjectDefinition): ObjectTypeDefinition {
        val fields =
            buildList {
                if (definition.implementsNode) {
                    add(
                        FieldDefinition
                            .newFieldDefinition()
                            .name("id")
                            .type(nonNull(TypeName("ID")))
                            .build(),
                    )
                }
                addAll(definition.fields.map(::fieldDefinition))
            }
        return ObjectTypeDefinition
            .newObjectTypeDefinition()
            .name(definition.name)
            .implementz(
                buildList {
                    if (definition.implementsNode) add(TypeName("Node"))
                    addAll(definition.interfaces.map(::TypeName))
                },
            ).fieldDefinitions(fields)
            .build()
    }

    private fun unionType(definition: UnionDefinitionSpec): UnionTypeDefinition =
        UnionTypeDefinition
            .newUnionTypeDefinition()
            .name(definition.name)
            .memberTypes(definition.members.map(::TypeName))
            .build()

    private fun enumType(definition: EnumDefinitionSpec): EnumTypeDefinition =
        EnumTypeDefinition
            .newEnumTypeDefinition()
            .name(definition.name)
            .enumValueDefinitions(
                definition.values.map { value ->
                    EnumValueDefinition.newEnumValueDefinition().name(value).build()
                },
            ).build()

    private fun inputObjectType(
        definition: InputObjectDefinitionSpec,
    ): InputObjectTypeDefinition =
        InputObjectTypeDefinition
            .newInputObjectDefinition()
            .name(definition.name)
            .inputValueDefinitions(
                definition.fields.map { field ->
                    InputValueDefinition
                        .newInputValueDefinition()
                        .name(field.name)
                        .type(inputType(field.type))
                        .build()
                },
            ).build()

    private fun fieldDefinition(field: FieldDefinitionSpec): FieldDefinition {
        val named = TypeName(field.type.namedType)
        val elementNullabilities =
            if (field.type.list) {
                listOf(field.type.elementNullable) + field.type.nestedElementNullabilities
            } else {
                emptyList()
            }
        val wrapped =
            elementNullabilities.asReversed().fold(named as Type<*>) { element, nullable ->
                ListType(if (nullable) element else nonNull(element))
            }
        val outputType =
            if (field.type.nullable) wrapped else nonNull(wrapped)
        return FieldDefinition
            .newFieldDefinition()
            .name(field.name)
            .type(outputType)
            .directives(
                if (field.isParentField) {
                    listOf(Directive.newDirective().name("parent").build())
                } else {
                    emptyList()
                },
            )
            .inputValueDefinitions(
                field.arguments.map { argument ->
                    val builder =
                        InputValueDefinition
                        .newInputValueDefinition()
                        .name(argument.name)
                        .type(inputType(argument.type))
                    argument.defaultValue?.let(builder::defaultValue)
                    builder.build()
                },
            ).build()
    }

    private fun inputType(type: InputTypeSpec): Type<*> {
        val unwrapped: Type<*> =
            when (type) {
                is ScalarInputTypeSpec -> TypeName(type.scalar.graphQLName)
                is InputObjectInputTypeSpec -> TypeName(type.name)
                is ListInputTypeSpec -> ListType(inputType(type.element))
            }
        return if (type.nullable) unwrapped else nonNull(unwrapped)
    }

    private fun schemaFeatures(
        objects: List<ObjectDefinition>,
        query: ObjectDefinition,
        interfaces: List<InterfaceDefinitionSpec>,
        unions: List<UnionDefinitionSpec>,
        inputObjects: List<InputObjectDefinitionSpec>,
        randomParentGraph: RandomParentGraph,
    ): SchemaFeatures {
        val objectIndices = objects.mapIndexed { index, objectType -> objectType.name to index }.toMap()
        val recursiveOutputFields =
            objects.flatMap { objectType ->
                objectType.fields.filter { field ->
                    val targetIndex = objectIndices[field.type.namedType]
                    targetIndex != null && targetIndex <= objectIndices.getValue(objectType.name)
                }
            }
        val fields = objects.flatMap(ObjectDefinition::fields) + query.fields
        val arguments = fields.flatMap(FieldDefinitionSpec::arguments)
        return SchemaFeatures(
            hasArguments = arguments.isNotEmpty(),
            hasMultipleArgumentField = fields.any { field -> field.arguments.size > 1 },
            hasScalarArguments = arguments.any { it.type is ScalarInputTypeSpec },
            hasListArguments = arguments.any { it.type is ListInputTypeSpec },
            hasInputObjectArguments = arguments.any { it.type is InputObjectInputTypeSpec },
            hasInputObjectListArguments =
                arguments.any { it.type.hasInputObjectInsideList() },
            hasInputObjects = inputObjects.isNotEmpty(),
            hasRecursiveInputTypes = inputObjects.haveTypeCycle(),
            hasOutputLists =
                (objects.flatMap(ObjectDefinition::fields) + query.fields)
                    .any { it.type.list },
            hasRecursiveOutputEdges = recursiveOutputFields.isNotEmpty(),
            hasNullableRecursiveOutputEdges = recursiveOutputFields.any { it.type.nullable },
            hasListRecursiveOutputEdges = recursiveOutputFields.any { it.type.list },
            hasImplementationArgumentDefaults =
                interfaces.any { interfaceType ->
                    interfaceType.members.any { memberName ->
                        val member = objects.single { it.name == memberName }
                        interfaceType.fields.any { interfaceField ->
                            val interfaceArguments =
                                interfaceField.arguments.mapTo(linkedSetOf()) { it.name }
                            member.fields
                                .singleOrNull { it.name == interfaceField.name }
                                ?.arguments
                                .orEmpty()
                                .any { argument ->
                                    argument.name !in interfaceArguments &&
                                        argument.defaultValue != null
                                }
                        }
                    }
                },
            hasInterfaces = interfaces.isNotEmpty(),
            hasUnions = unions.isNotEmpty(),
            maximumParentChainDepth =
                maxOf(
                    if (config[ParentFieldsEnabled]) 3 else 0,
                    randomParentGraph.maximumChainDepth,
                ),
            randomParentFieldCount = randomParentGraph.objects.size,
            randomParentListProducerCount = randomParentGraph.listProducerCount,
            randomParentAbstractTargetCount = randomParentGraph.abstractTargetCount,
        )
    }

    private fun List<InputObjectDefinitionSpec>.haveTypeCycle(): Boolean {
        val inputObjectNames = map(InputObjectDefinitionSpec::name).toSet()
        val edges =
            associate { definition ->
                definition.name to
                    definition.fields
                        .flatMap { it.type.referencedInputObjects() }
                        .filter(inputObjectNames::contains)
            }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun reachesCycle(name: String): Boolean {
            if (name in visiting) return true
            if (!visited.add(name)) return false
            visiting += name
            val found = edges.getValue(name).any(::reachesCycle)
            visiting -= name
            return found
        }

        return any { reachesCycle(it.name) }
    }

    private fun InputTypeSpec.referencedInputObjects(): List<String> =
        when (this) {
            is ScalarInputTypeSpec -> emptyList()
            is InputObjectInputTypeSpec -> listOf(name)
            is ListInputTypeSpec -> element.referencedInputObjects()
        }

    private fun InputTypeSpec.hasInputObjectInsideList(
        insideList: Boolean = false,
    ): Boolean =
        when (this) {
            is ScalarInputTypeSpec -> false
            is InputObjectInputTypeSpec -> insideList
            is ListInputTypeSpec -> element.hasInputObjectInsideList(insideList = true)
        }

    private fun nonNull(type: Type<*>): NonNullType =
        NonNullType(type)

    private fun List<ObjectDefinition>.withAbstractOutputTargets(
        interfaces: List<InterfaceDefinitionSpec>,
        unions: List<UnionDefinitionSpec>,
    ): List<ObjectDefinition> {
        val objectIndices = mapIndexed { index, objectType -> objectType.name to index }.toMap()
        val abstractMembers =
            interfaces.associate { it.name to it.members } +
                unions.associate { it.name to it.members }
        if (
            abstractMembers.isEmpty() ||
            config[PassiveAbstractOutputTypeWeight] == 0.0 ||
            config[MinimumSelectionDepth] > 0
        ) {
            return this
        }

        return map { objectType ->
            val ownerIndex = objectIndices.getValue(objectType.name)
            objectType.copy(
                fields =
                    objectType.fields.map transformField@ { field ->
                        val targetIndex = objectIndices[field.type.namedType] ?: return@transformField field
                        if (targetIndex <= ownerIndex) return@transformField field
                        if (!chance(config[PassiveAbstractOutputTypeWeight])) return@transformField field
                        val candidates =
                            abstractMembers
                                .filterValues { members ->
                                    members.all { objectIndices.getValue(it) > ownerIndex }
                                }.keys
                                .toList()
                        candidates
                            .takeIf(List<String>::isNotEmpty)
                            ?.let { candidateNames ->
                                field.copy(
                                    type =
                                        field.type.copy(
                                            namedType = Arb.element(candidateNames).next(random),
                                        ),
                                )
                            }
                            ?: field
                    },
            )
        }
    }

    private fun chance(weight: Double): Boolean =
        Arb.double(0.0, 1.0).next(random) < weight

    private fun nonEmptySubset(values: List<String>): Set<String> =
        values
            .filter { chance(0.6) }
            .toSet()
            .ifEmpty { setOf(Arb.element(values).next(random)) }
}
