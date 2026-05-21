package viaduct.gradle

open class ViaductApplicationExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name for generated GRT classes. */
    val grtPackageName = objects.property(String::class.java).convention("viaduct.api.grts")

    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)
}
