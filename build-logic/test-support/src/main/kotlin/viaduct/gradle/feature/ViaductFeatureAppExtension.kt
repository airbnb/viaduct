package viaduct.gradle.feature

import org.gradle.api.Project
import org.gradle.api.provider.Property

/**
 * Extension for configuring FeatureApp code generation with sensible defaults
 * Can be used with empty configuration: viaductFeatureApp {}
 */
open class ViaductFeatureAppExtension(project: Project) {
    /**
     * Base package name for generated code
     */
    val basePackageName: Property<String> = project.objects.property(String::class.java)
        .convention("generated.featureapp")

    val fileNamePattern: Property<String> = project.objects.property(String::class.java)
        .convention(".*(FeatureApp|FeatureAppTest).*")

    /**
     * The source set that FeatureApp tests belong to.
     * Default: "test".
     */
    val sourceSetName: Property<String> = project.objects.property(String::class.java)
        .convention("test")
}
