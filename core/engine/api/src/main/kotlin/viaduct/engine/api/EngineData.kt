package viaduct.engine.api

/** A simple engine value. */
typealias EngineSimpleData = Any

/** An engine input value. */
typealias EngineInputData = Any

/** A list of nullable engine input values. */
typealias EngineInputListData = List<EngineInputData?>

/** A map of nullable engine input values keyed by field name. */
typealias EngineInputObjectData = Map<String, EngineInputData?>

/** An engine output value. */
typealias EngineOutputData = Any

/** A list of nullable engine output values. */
typealias EngineOutputListData = List<EngineOutputData?>
