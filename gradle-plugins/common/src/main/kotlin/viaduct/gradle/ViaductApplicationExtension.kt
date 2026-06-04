package viaduct.gradle

open class ViaductApplicationExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)
}
