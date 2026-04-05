package viaduct.gradle.featureappcontract

import org.gradle.api.Project
import org.gradle.api.provider.Property

/**
 * Extension for configuring FeatureApp contract test code generation.
 */
open class ViaductFeatureAppContractExtension(project: Project) {
    /**
     * Base package name for generated code
     */
    val basePackageName: Property<String> = project.objects.property(String::class.java)
        .convention("generated.featureapp")

    val fileNamePattern: Property<String> = project.objects.property(String::class.java)
        .convention(".*(FeatureApp|FeatureAppTest|ContractTest).*")

    /**
     * The source set that FeatureApp tests belong to.
     * Default: "test".
     */
    val sourceSetName: Property<String> = project.objects.property(String::class.java)
        .convention("test")
}
