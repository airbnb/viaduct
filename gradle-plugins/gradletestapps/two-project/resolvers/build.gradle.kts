import org.gradle.api.Plugin
import org.gradle.api.Project
import viaduct.gradle.ViaductSchemaContributions

plugins {
    id("conventions.kotlin")
    id("conventions.ksp")
    id("com.airbnb.viaduct.module-gradle-plugin")
}

dependencies {
    implementation("com.airbnb.viaduct:api")
}

class TestSchemaContributionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val prepareSchemaContribution = project.tasks.register("prepareTestSchemaContribution") {
            val output = project.layout.buildDirectory.file("test-schema-contribution/contribution.graphqls")
            outputs.file(output)
            doLast {
                output.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText("directive @fromModuleExtension on FIELD_DEFINITION")
                }
            }
        }
        val contributionFiles = project.objects.fileCollection().from(prepareSchemaContribution)
        project.extensions.getByType(ViaductSchemaContributions::class.java).register(
            "com.example.test-schema-contribution",
            contributionFiles,
        )
    }
}

apply<TestSchemaContributionPlugin>()
