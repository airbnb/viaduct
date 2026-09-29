package semantics.arbitrary

/**
 * Immutable generator configuration modeled after Viaduct arbitrary's typed configuration map.
 */
class Config private constructor(
    private val values: Map<ConfigKey<*>, Any?>,
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: ConfigKey<T>): T = values[key] as? T ?: key.default

    operator fun <T> plus(value: Pair<ConfigKey<T>, T>): Config {
        value.first.validate(value.second)?.let { message ->
            throw IllegalArgumentException("$message: ${value.second}")
        }
        return Config(values + value)
    }

    operator fun plus(overrides: Config): Config = Config(values + overrides.values)

    internal fun resolvedValues(): Map<ConfigKey<*>, Any> =
        ConfigKeys.all.associateWith { key ->
            values[key] ?: requireNotNull(key.default)
        }

    companion object {
        val default: Config = Config(emptyMap())

        internal fun fromResolvedValues(values: Map<ConfigKey<*>, Any>): Config = Config(values)
    }
}

open class ConfigKey<T>(
    val wireName: String,
    val wireType: ConfigValueType,
    val default: T,
    val validate: (T) -> String?,
)

enum class ConfigValueType {
    BOOLEAN,
    INTEGER,
    DOUBLE,
    INTEGER_RANGE,
}

private fun positive(value: Int): String? = if (value > 0) null else "Value must be positive"

private fun nonNegative(value: Int): String? = if (value >= 0) null else "Value must be non-negative"

private fun range(value: IntRange): String? = if (!value.isEmpty() && value.first >= 0) null else "Range must be non-empty and non-negative"

private fun weight(value: Double): String? = if (value in 0.0..1.0) null else "Weight must be between 0.0 and 1.0"

object SchemaObjectCount : ConfigKey<IntRange>("schemaObjectCount", ConfigValueType.INTEGER_RANGE, 1..4, ::range)

object ObjectFieldCount : ConfigKey<IntRange>("objectFieldCount", ConfigValueType.INTEGER_RANGE, 1..4, ::range)

object QueryFieldCount : ConfigKey<IntRange>("queryFieldCount", ConfigValueType.INTEGER_RANGE, 1..3, ::range)

object RootQueryFieldCount : ConfigKey<IntRange>("rootQueryFieldCount", ConfigValueType.INTEGER_RANGE, 0..0, ::range)

object NestedQueryFieldCount : ConfigKey<IntRange>("nestedQueryFieldCount", ConfigValueType.INTEGER_RANGE, 1..3, ::range)

object NestedQueryScalarFieldWeight : ConfigKey<Double>("nestedQueryScalarFieldWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object QueryScalarFieldWeight : ConfigKey<Double>("queryScalarFieldWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ObjectOutputFieldWeight : ConfigKey<Double>("objectOutputFieldWeight", ConfigValueType.DOUBLE, 0.45, ::weight)

object FieldArgumentWeight : ConfigKey<Double>("fieldArgumentWeight", ConfigValueType.DOUBLE, 0.3, ::weight)

object FieldArgumentCount : ConfigKey<IntRange>("fieldArgumentCount", ConfigValueType.INTEGER_RANGE, 1..3, ::range)

object InputScalarValueRange : ConfigKey<IntRange>("inputScalarValueRange", ConfigValueType.INTEGER_RANGE, 0..10_000, ::range)

object ImplementationArgumentDefaultWeight : ConfigKey<Double>("implementationArgumentDefaultWeight", ConfigValueType.DOUBLE, 0.3, ::weight)

object InputObjectCount : ConfigKey<IntRange>("inputObjectCount", ConfigValueType.INTEGER_RANGE, 0..2, ::range)

object InputObjectFieldCount : ConfigKey<IntRange>("inputObjectFieldCount", ConfigValueType.INTEGER_RANGE, 1..3, ::range)

object InputObjectTypeWeight : ConfigKey<Double>("inputObjectTypeWeight", ConfigValueType.DOUBLE, 0.25, ::weight)

object InputListTypeWeight : ConfigKey<Double>("inputListTypeWeight", ConfigValueType.DOUBLE, 0.2, ::weight)

object MaxInputTypeDepth : ConfigKey<Int>("maxInputTypeDepth", ConfigValueType.INTEGER, 2, ::nonNegative)

object ExplicitFieldResolverWeight : ConfigKey<Double>("explicitFieldResolverWeight", ConfigValueType.DOUBLE, 0.25, ::weight)

object SometimesPassiveFieldWeight : ConfigKey<Double>("sometimesPassiveFieldWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object RootFieldReferencesEnabled : ConfigKey<Boolean>("rootFieldReferencesEnabled", ConfigValueType.BOOLEAN, false, { null })

object RootFieldReferenceWeight : ConfigKey<Double>("rootFieldReferenceWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ListTypeWeight : ConfigKey<Double>("listTypeWeight", ConfigValueType.DOUBLE, 0.2, ::weight)

object MaxOutputListDepth : ConfigKey<Int>("maxOutputListDepth", ConfigValueType.INTEGER, 1, ::positive)

object PassiveAbstractOutputTypeWeight : ConfigKey<Double>("passiveAbstractOutputTypeWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object NullableTypeWeight : ConfigKey<Double>("nullableTypeWeight", ConfigValueType.DOUBLE, 0.45, ::weight)

object RecursiveOutputEdgeWeight : ConfigKey<Double>("recursiveOutputEdgeWeight", ConfigValueType.DOUBLE, 0.2, ::weight)

object NullValueWeight : ConfigKey<Double>("nullValueWeight", ConfigValueType.DOUBLE, 0.15, ::weight)

object ErrorValueWeight : ConfigKey<Double>("errorValueWeight", ConfigValueType.DOUBLE, 0.05, ::weight)

object AliasWeight : ConfigKey<Double>("aliasWeight", ConfigValueType.DOUBLE, 0.2, ::weight)

object DuplicateSelectionWeight : ConfigKey<Double>("duplicateSelectionWeight", ConfigValueType.DOUBLE, 0.15, ::weight)

object MinimumSelectionDepth : ConfigKey<Int>("minimumSelectionDepth", ConfigValueType.INTEGER, 0, ::nonNegative)

object MaxSelectionDepth : ConfigKey<Int>("maxSelectionDepth", ConfigValueType.INTEGER, 4, ::positive)

object ListValueSize : ConfigKey<IntRange>("listValueSize", ConfigValueType.INTEGER_RANGE, 0..3, ::range)

object ArgumentsEnabled : ConfigKey<Boolean>("argumentsEnabled", ConfigValueType.BOOLEAN, true, { null })

object InputObjectsEnabled : ConfigKey<Boolean>("inputObjectsEnabled", ConfigValueType.BOOLEAN, true, { null })

object RecursiveInputTypesEnabled : ConfigKey<Boolean>("recursiveInputTypesEnabled", ConfigValueType.BOOLEAN, true, { null })

object RecursiveOutputEdgesEnabled : ConfigKey<Boolean>("recursiveOutputEdgesEnabled", ConfigValueType.BOOLEAN, true, { null })

object ParentFieldsEnabled : ConfigKey<Boolean>("parentFieldsEnabled", ConfigValueType.BOOLEAN, false, { null })

object RandomParentFieldsEnabled : ConfigKey<Boolean>("randomParentFieldsEnabled", ConfigValueType.BOOLEAN, false, { null })

object QueryFragmentsEnabled : ConfigKey<Boolean>("queryFragmentsEnabled", ConfigValueType.BOOLEAN, true, { null })

object ResolverQueryFragmentsEnabled : ConfigKey<Boolean>("resolverQueryFragmentsEnabled", ConfigValueType.BOOLEAN, false, { null })

object ResolverQueryFragmentWeight : ConfigKey<Double>("resolverQueryFragmentWeight", ConfigValueType.DOUBLE, 1.0, ::weight)

object InterfacesEnabled : ConfigKey<Boolean>("interfacesEnabled", ConfigValueType.BOOLEAN, true, { null })

object UnionsEnabled : ConfigKey<Boolean>("unionsEnabled", ConfigValueType.BOOLEAN, true, { null })

object ListsEnabled : ConfigKey<Boolean>("listsEnabled", ConfigValueType.BOOLEAN, true, { null })

object NodeResolversEnabled : ConfigKey<Boolean>("nodeResolversEnabled", ConfigValueType.BOOLEAN, true, { null })

object SelectiveNodeResolversEnabled : ConfigKey<Boolean>("selectiveNodeResolversEnabled", ConfigValueType.BOOLEAN, false, { null })

object NodeObjectWeight : ConfigKey<Double>("nodeObjectWeight", ConfigValueType.DOUBLE, 0.35, ::weight)

object ResolverFragmentsEnabled : ConfigKey<Boolean>("resolverFragmentsEnabled", ConfigValueType.BOOLEAN, true, { null })

object ResolverFragmentWeight : ConfigKey<Double>("resolverFragmentWeight", ConfigValueType.DOUBLE, 0.65, ::weight)

object ResolverFragmentDepth : ConfigKey<Int>("resolverFragmentDepth", ConfigValueType.INTEGER, 2, ::nonNegative)

object ResolverFragmentSelectionCount : ConfigKey<IntRange>("resolverFragmentSelectionCount", ConfigValueType.INTEGER_RANGE, 0..0, ::range)

object ResolverFragmentLongTailWeight : ConfigKey<Double>("resolverFragmentLongTailWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ResolverFragmentLongTailSelectionCount : ConfigKey<IntRange>("resolverFragmentLongTailSelectionCount", ConfigValueType.INTEGER_RANGE, 0..0, ::range)

object ResolverFragmentArgumentFieldWeight : ConfigKey<Double>("resolverFragmentArgumentFieldWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ResolverArgumentErrorWeight : ConfigKey<Double>("resolverArgumentErrorWeight", ConfigValueType.DOUBLE, 0.05, ::weight)

object ResolverVariablesEnabled : ConfigKey<Boolean>("resolverVariablesEnabled", ConfigValueType.BOOLEAN, false, { null })

object ResolverFromArgumentVariablesEnabled : ConfigKey<Boolean>("resolverFromArgumentVariablesEnabled", ConfigValueType.BOOLEAN, false, { null })

object ResolverFromProviderVariablesEnabled : ConfigKey<Boolean>("resolverFromProviderVariablesEnabled", ConfigValueType.BOOLEAN, false, { null })

object ResolverFromObjectFieldVariablesEnabled : ConfigKey<Boolean>("resolverFromObjectFieldVariablesEnabled", ConfigValueType.BOOLEAN, true, { null })

object ResolverFromQueryFieldVariablesEnabled : ConfigKey<Boolean>("resolverFromQueryFieldVariablesEnabled", ConfigValueType.BOOLEAN, false, { null })

object ResolverFromArgumentNestedPathWeight :
    ConfigKey<Double>("resolverFromArgumentNestedPathWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ResolverVariableWeight : ConfigKey<Double>("resolverVariableWeight", ConfigValueType.DOUBLE, 0.5, ::weight)

object ResolverVariableCount : ConfigKey<IntRange>("resolverVariableCount", ConfigValueType.INTEGER_RANGE, 1..3, ::range)

object ResolverVariableSingletonCoercionEnabled : ConfigKey<Boolean>("resolverVariableSingletonCoercionEnabled", ConfigValueType.BOOLEAN, false, { null })

object ResolverLiteralVariableConvergenceWeight : ConfigKey<Double>("resolverLiteralVariableConvergenceWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ResolverNestedProviderPathWeight : ConfigKey<Double>("resolverNestedProviderPathWeight", ConfigValueType.DOUBLE, 0.5, ::weight)

object ResolverFromFieldProviderPathLength :
    ConfigKey<IntRange>("resolverFromFieldProviderPathLength", ConfigValueType.INTEGER_RANGE, 1..Int.MAX_VALUE, ::range)

object ResolverFromFieldVariableUseDepth :
    ConfigKey<IntRange>("resolverFromFieldVariableUseDepth", ConfigValueType.INTEGER_RANGE, 1..Int.MAX_VALUE, ::range)

object ResolverFromFieldPassiveUseWeight : ConfigKey<Double>("resolverFromFieldPassiveUseWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ResolverFromFieldVariableOwnerUseWeight : ConfigKey<Double>("resolverFromFieldVariableOwnerUseWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ResolverFromFieldProviderArgumentVariableWeight : ConfigKey<Double>("resolverFromFieldProviderArgumentVariableWeight", ConfigValueType.DOUBLE, 0.0, ::weight)

object ResolverVariablesOnNonQueryFieldsOnly : ConfigKey<Boolean>("resolverVariablesOnNonQueryFieldsOnly", ConfigValueType.BOOLEAN, false, { null })

object ResolverFromFieldVariableOwnerLimit :
    ConfigKey<Int>("resolverFromFieldVariableOwnerLimit", ConfigValueType.INTEGER, Int.MAX_VALUE, ::positive)

internal object ConfigKeys {
    val all: List<ConfigKey<*>> =
        listOf(
            SchemaObjectCount,
            ObjectFieldCount,
            QueryFieldCount,
            RootQueryFieldCount,
            NestedQueryFieldCount,
            NestedQueryScalarFieldWeight,
            QueryScalarFieldWeight,
            ObjectOutputFieldWeight,
            FieldArgumentWeight,
            FieldArgumentCount,
            InputScalarValueRange,
            ImplementationArgumentDefaultWeight,
            InputObjectCount,
            InputObjectFieldCount,
            InputObjectTypeWeight,
            InputListTypeWeight,
            MaxInputTypeDepth,
            ExplicitFieldResolverWeight,
            SometimesPassiveFieldWeight,
            RootFieldReferencesEnabled,
            RootFieldReferenceWeight,
            ListTypeWeight,
            MaxOutputListDepth,
            PassiveAbstractOutputTypeWeight,
            NullableTypeWeight,
            RecursiveOutputEdgeWeight,
            NullValueWeight,
            ErrorValueWeight,
            AliasWeight,
            DuplicateSelectionWeight,
            MinimumSelectionDepth,
            MaxSelectionDepth,
            ListValueSize,
            ArgumentsEnabled,
            InputObjectsEnabled,
            RecursiveInputTypesEnabled,
            RecursiveOutputEdgesEnabled,
            ParentFieldsEnabled,
            RandomParentFieldsEnabled,
            QueryFragmentsEnabled,
            ResolverQueryFragmentsEnabled,
            ResolverQueryFragmentWeight,
            InterfacesEnabled,
            UnionsEnabled,
            ListsEnabled,
            NodeResolversEnabled,
            SelectiveNodeResolversEnabled,
            NodeObjectWeight,
            ResolverFragmentsEnabled,
            ResolverFragmentWeight,
            ResolverFragmentDepth,
            ResolverFragmentSelectionCount,
            ResolverFragmentLongTailWeight,
            ResolverFragmentLongTailSelectionCount,
            ResolverFragmentArgumentFieldWeight,
            ResolverArgumentErrorWeight,
            ResolverVariablesEnabled,
            ResolverFromArgumentVariablesEnabled,
            ResolverFromProviderVariablesEnabled,
            ResolverFromObjectFieldVariablesEnabled,
            ResolverFromQueryFieldVariablesEnabled,
            ResolverFromArgumentNestedPathWeight,
            ResolverVariableWeight,
            ResolverVariableCount,
            ResolverVariableSingletonCoercionEnabled,
            ResolverLiteralVariableConvergenceWeight,
            ResolverNestedProviderPathWeight,
            ResolverFromFieldProviderPathLength,
            ResolverFromFieldVariableUseDepth,
            ResolverFromFieldPassiveUseWeight,
            ResolverFromFieldVariableOwnerUseWeight,
            ResolverFromFieldProviderArgumentVariableWeight,
            ResolverVariablesOnNonQueryFieldsOnly,
            ResolverFromFieldVariableOwnerLimit,
        )

    init {
        require(all.map(ConfigKey<*>::wireName).distinct().size == all.size)
    }
}

data class TestCaseCount(
    val schemas: Int = 10,
    val registriesPerSchema: Int = 3,
    val queriesPerSchema: Int = 5,
) {
    init {
        require(schemas > 0)
        require(registriesPerSchema > 0)
        require(queriesPerSchema > 0)
    }
}
