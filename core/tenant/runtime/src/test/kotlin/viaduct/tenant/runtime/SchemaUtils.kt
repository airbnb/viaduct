package viaduct.tenant.runtime

import kotlin.reflect.KClass

val KClass<*>.packageName: String get() =
    qualifiedName!!.split(".").dropLast(1).joinToString(".")
