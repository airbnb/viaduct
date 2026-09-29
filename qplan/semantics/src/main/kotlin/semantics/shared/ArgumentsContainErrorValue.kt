package semantics.shared

import model.Arguments

internal fun Arguments.Ground.argumentsContainErrorValue(): Boolean = this == Arguments.Error
