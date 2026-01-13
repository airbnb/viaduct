package viaduct.gradle

open class ViaductApplicationExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name for generated GRT classes. */
    val grtPackageName = objects.property(String::class.java).convention("viaduct.api.grts")

    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)

    /** Version of the Viaduct BOM to use. Defaults to the project version. */
    val bomVersion = objects.property(String::class.java)

    /** Whether to automatically apply the Viaduct BOM platform dependency. Defaults to true. */
    val applyBOM = objects.property(Boolean::class.java).convention(true)

    /** Which Viaduct artifacts to automatically add as dependencies. Defaults to all common ones. */
    val viaductDependencies = objects.setProperty(String::class.java).convention(ViaductPluginCommon.BOM.DEFAULT_APPLICATION_ARTIFACTS)

    /** Which Viaduct artifacts to automatically add as test dependencies. */
    val viaductTestDependencies = objects.setProperty(String::class.java).convention(ViaductPluginCommon.BOM.DEFAULT_APPLICATION_TEST_ARTIFACTS)

    /** Which Viaduct testFixtures to automatically add as test dependencies. */
    val viaductTestFixtures = objects.setProperty(String::class.java).convention(ViaductPluginCommon.BOM.DEFAULT_TEST_FIXTURES)

    /** Port for the development server. Defaults to 8080. Set to 0 for dynamic port allocation. */
    val servePort = objects.property(Int::class.java).convention(8080)

    /** Host address for the development server. Defaults to "0.0.0.0". */
    val serveHost = objects.property(String::class.java).convention("0.0.0.0")
}
