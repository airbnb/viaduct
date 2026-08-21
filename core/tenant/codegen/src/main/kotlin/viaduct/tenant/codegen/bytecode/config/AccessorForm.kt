package viaduct.tenant.codegen.bytecode.config

/** The forms a generated GRT field accessor takes, one per reading behavior. */
enum class AccessorForm(
    val suffix: String,
    val fetchMethod: String,
    val nullable: Boolean,
) {
    /** Throws on any failure. */
    STRICT("OrThrow", "getInternal", false),

    /** The bare `getFoo` name, identical to [STRICT] while call sites migrate onto it. */
    LEGACY_STRICT("", "getInternal", false),

    /** Returns null for data-side failures. Tenant and framework bugs still propagate. */
    SOFT("OrNull", "getOrNullInternal", true),
    ;

    fun methodName(fieldAccessorName: String): String = fieldAccessorName + suffix
}
