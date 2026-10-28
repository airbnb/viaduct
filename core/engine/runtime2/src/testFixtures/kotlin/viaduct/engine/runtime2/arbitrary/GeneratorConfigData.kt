package viaduct.engine.runtime2.arbitrary

const val GENERATOR_CONFIG_FORMAT_VERSION = 5

data class IntRangeConfig(
    val minimum: Int,
    val maximum: Int,
) {
    fun toRange(): IntRange = minimum..maximum

    companion object {
        fun from(range: IntRange): IntRangeConfig = IntRangeConfig(range.first, range.last)
    }
}

data class GeneratorConfigData(
    val formatVersion: Int,
    val id: String,
    val booleans: Map<String, Boolean>,
    val integers: Map<String, Int>,
    val doubles: Map<String, Double>,
    val ranges: Map<String, IntRangeConfig>,
) {
    fun toConfig(): Config {
        require(formatVersion == GENERATOR_CONFIG_FORMAT_VERSION) {
            "Unsupported generator config formatVersion $formatVersion"
        }
        require(id.isNotBlank()) { "Generator config id must not be blank" }
        val groups: Map<ConfigValueType, Map<String, *>> =
            mapOf(
                ConfigValueType.BOOLEAN to booleans,
                ConfigValueType.INTEGER to integers,
                ConfigValueType.DOUBLE to doubles,
                ConfigValueType.INTEGER_RANGE to ranges,
            )
        val knownNames = ConfigKeys.all.mapTo(linkedSetOf(), ConfigKey<*>::wireName)
        val suppliedNames =
            groups.values.flatMapTo(linkedSetOf()) { values -> values.keys }
        require(suppliedNames == knownNames) {
            "Generator config $id keys differ from the supported key set; " +
                "missing=${knownNames - suppliedNames}, unknown=${suppliedNames - knownNames}"
        }
        val duplicateNames =
            groups.values
                .flatMap { values -> values.keys }
                .groupingBy { name -> name }
                .eachCount()
                .filterValues { count -> count != 1 }
                .keys
        require(duplicateNames.isEmpty()) {
            "Generator config $id contains keys in multiple type groups: $duplicateNames"
        }
        val resolved =
            ConfigKeys.all.associateWith { key ->
                val value: Any =
                    when (key.wireType) {
                        ConfigValueType.BOOLEAN ->
                            requireNotNull(booleans[key.wireName])
                        ConfigValueType.INTEGER ->
                            requireNotNull(integers[key.wireName])
                        ConfigValueType.DOUBLE ->
                            requireNotNull(doubles[key.wireName])
                        ConfigValueType.INTEGER_RANGE ->
                            requireNotNull(ranges[key.wireName]).toRange()
                    }
                validateValue(key, value)
                value
            }
        return Config.fromResolvedValues(resolved)
    }

    companion object {
        fun from(
            id: String,
            config: Config,
        ): GeneratorConfigData {
            require(id.isNotBlank()) { "Generator config id must not be blank" }
            val booleans = linkedMapOf<String, Boolean>()
            val integers = linkedMapOf<String, Int>()
            val doubles = linkedMapOf<String, Double>()
            val ranges = linkedMapOf<String, IntRangeConfig>()
            config.resolvedValues().forEach { (key, value) ->
                when (key.wireType) {
                    ConfigValueType.BOOLEAN ->
                        booleans[key.wireName] = value as Boolean
                    ConfigValueType.INTEGER ->
                        integers[key.wireName] = value as Int
                    ConfigValueType.DOUBLE ->
                        doubles[key.wireName] = value as Double
                    ConfigValueType.INTEGER_RANGE ->
                        ranges[key.wireName] = IntRangeConfig.from(value as IntRange)
                }
            }
            return GeneratorConfigData(
                formatVersion = GENERATOR_CONFIG_FORMAT_VERSION,
                id = id,
                booleans = booleans,
                integers = integers,
                doubles = doubles,
                ranges = ranges,
            )
        }
    }
}

private fun validateValue(
    key: ConfigKey<*>,
    value: Any,
) {
    @Suppress("UNCHECKED_CAST")
    val message = (key as ConfigKey<Any>).validate(value)
    require(message == null) {
        "$message for generator config key ${key.wireName}: $value"
    }
}
