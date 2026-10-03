package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.Arguments

internal fun Arguments.Ground.argumentsContainErrorValue(): Boolean = this == Arguments.Error
