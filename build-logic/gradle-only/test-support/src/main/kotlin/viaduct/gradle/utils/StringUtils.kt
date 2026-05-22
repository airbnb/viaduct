package viaduct.gradle.utils

/**
 * Extension function to capitalize a String in a non-deprecated way
 */
internal fun String.capitalize() = this.replaceFirstChar { it.uppercase() }
