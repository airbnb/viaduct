@file:OptIn(viaduct.apiannotations.InternalApi::class)

import fixtures.GenerateFinalSchema
import fixtures.PluginSchemaResources
import viaduct.gradle.ViaductApplicationConventions
import viaduct.gradle.ViaductSchemaValidator
import viaduct.gradle.task.AssembleCentralSchemaTask

plugins {
    id("conventions.kotlin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
    implementation("com.airbnb.viaduct:runtime")
}

val alphaFixtureDependencies = configurations.dependencyScope("alphaFixtureDependencies")
val alphaResolverBases = configurations.resolvable("alphaResolverBases") {
    extendsFrom(alphaFixtureDependencies.get())
    isTransitive = false
}
dependencies {
    add(alphaFixtureDependencies.name, project(mapOf("path" to ":multi-project:alpha", "configuration" to "fixtureResolverBases")))
}

val betaFixtureDependencies = configurations.dependencyScope("betaFixtureDependencies")
val betaResolverBases = configurations.resolvable("betaResolverBases") {
    extendsFrom(betaFixtureDependencies.get())
    isTransitive = false
}
dependencies {
    add(betaFixtureDependencies.name, project(mapOf("path" to ":multi-project:beta", "configuration" to "fixtureResolverBases")))
}

val copyAlphaResolverBases = tasks.register<Sync>("copyAlphaResolverBases") {
    from(alphaResolverBases)
    into(layout.buildDirectory.dir("fixture-outputs/alpha"))
}
val copyBetaResolverBases = tasks.register<Sync>("copyBetaResolverBases") {
    from(betaResolverBases)
    into(layout.buildDirectory.dir("fixture-outputs/beta"))
}

tasks.withType<Test>().configureEach {
    systemProperty("projectBuildDir", layout.buildDirectory.asFile.get().absolutePath)
    inputs.dir(copyAlphaResolverBases.map { it.destinationDir }).withPropertyName("alphaResolverBases").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("alphaResolverBasesDir", layout.buildDirectory.dir("fixture-outputs/alpha").get().asFile.absolutePath)
    inputs.dir(copyBetaResolverBases.map { it.destinationDir }).withPropertyName("betaResolverBases").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("betaResolverBasesDir", layout.buildDirectory.dir("fixture-outputs/beta").get().asFile.absolutePath)
}

val fixtureSdl = resources.text.fromUri(requireNotNull(
    PluginSchemaResources::class.java.getResource("/dev/example/fixture/schema/directives.graphqls")
))
val generatedFixtureSdl = tasks.register<Sync>("generateFixturePluginSchema") {
    from(layout.projectDirectory.file("fixture-generated.graphqls"))
    into(layout.buildDirectory.dir("fixture-plugin-schema"))
}
extensions.getByType<ViaductApplicationConventions>().pluginSchema.apply {
    into("dev.example.fixture/source/dev/example/fixture/schema") {
        from(fixtureSdl) { rename { "directives.graphqls" } }
    }
    into("dev.example.fixture/generated/dev.example.generator") {
        from(generatedFixtureSdl.map { it.destinationDir })
    }
}

val applicationConventions = extensions.getByType<ViaductApplicationConventions>()
dependencies {
    add(applicationConventions.schemaValidationPlugins.name, project(":validation-rules"))
}
val finalFixtureSchema = tasks.register<GenerateFinalSchema>("generateFinalFixtureSchema") {
    centralSchema.set(applicationConventions.centralSchemaDirectory)
    outputDirectory.set(layout.buildDirectory.dir("fixture-final-schema"))
}
applicationConventions.pluginFinalSchema.into("dev.example.fixture/generated/dev.example.final-generator") {
    from(finalFixtureSchema.flatMap { it.outputDirectory })
}
tasks.withType<Test>().configureEach {
    inputs.dir(applicationConventions.finalSchemaDirectory).withPropertyName("finalSchema").withPathSensitivity(PathSensitivity.RELATIVE)
}

abstract class VerifyFoundationValidation : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val centralSchema: DirectoryProperty

    @get:Classpath
    abstract val validationClasspath: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val cases = mapOf(
            "KEY_INVALID_FIELDS" to "extend type FixtureProduct @key(fields: \"missing\")",
            "PROVIDES_INVALID_FIELDS" to "extend type Query { invalidFixtureProduct: FixtureProduct @provides(fields: \"missing\") }",
        )
        cases.forEach { (code, declaration) ->
            val sourceRoot = temporaryDir.resolve(code)
            sourceRoot.deleteRecursively()
            centralSchema.get().asFile.copyRecursively(sourceRoot)
            val source = "schema/module/alpha/source/invalid-foundation.graphqls"
            sourceRoot.resolve(source).writeText(declaration)
            val errors = ViaductSchemaValidator(
                logger,
                sourceRoot = sourceRoot,
                validationClasspath = validationClasspath.files,
            ).validateSchema(sourceRoot.walkTopDown().filter { it.isFile && it.extension == "graphqls" }.toList())

            check(errors.size == 1) { "Expected one $code failure through the resolved classpath, got $errors" }
            val message = errors.single().message
            check("[$code]" in message && source in message && "viaduct.gradle.federation.FoundationValidationRuleProvider" in message) {
                "Expected an attributed $code failure from the Foundation provider, got $message"
            }
        }
    }
}

val verifyFoundationValidation = tasks.register<VerifyFoundationValidation>("verifyFoundationValidation") {
    centralSchema.set(applicationConventions.centralSchemaDirectory)
    validationClasspath.from(tasks.named<AssembleCentralSchemaTask>("assembleViaductCentralSchema").map { it.schemaValidationClasspath })
}
tasks.withType<Test>().configureEach {
    dependsOn(verifyFoundationValidation)
}
