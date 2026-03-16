package viaduct.gradle.common

import org.gradle.api.Project
import org.gradle.api.provider.Property

/**
 * Base extension for configuring FeatureApp code generation with sensible defaults.
 * Subclassed by both Kotlin and Java FeatureApp extensions for backwards compatibility
 * with convention `.gradle.kts` files.
 */
open class ViaductFeatureAppExtensionBase(project: Project) {
    /**
     * Base package name for generated code
     */
    val basePackageName: Property<String> = project.objects.property(String::class.java)
        .convention("generated.featureapp")

    val fileNamePattern: Property<String> = project.objects.property(String::class.java)
        .convention(".*(FeatureApp|FeatureAppTest).*")
}
