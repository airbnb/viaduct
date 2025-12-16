/**
 * Add this to projects that have a companion -integration-tests project
 *
 * It will publishes JaCoCo's exec files and source jars to allow us to make
 * a comprehensive test-coverage report.
 */

import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

val jacocoExecElements by configurations.creating {
    description = "JaCoCo exec data produced by ${project.path}"
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.VERIFICATION))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("jacoco-exec"))
    }
}

// Make source available for integration-test's coverate report
plugins.withType<JavaPlugin> {
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }
}

// Publish each Test task's JaCoCo destination file (containing raw coverage data) as an outgoing artifact
plugins.withId("jacoco") {
    tasks.withType<Test>().configureEach {
        extensions.findByType(JacocoTaskExtension::class.java)?.destinationFile?.let { execFile ->
            artifacts {
                add(jacocoExecElements.name, execFile) {
                    builtBy(this@configureEach)
                    type = "jacoco-exec"
                }
            }
        } ?: throw GradleException(
                "Jacoco destinationFile is null for task $path. " +
                    "Ensure your Jacoco convention sets it before this plugin, " +
                    "or switch to the 'set-if-null' pattern."
        )
    }
}
