package viaduct.gradle.feature

import org.gradle.api.tasks.TaskAction
import viaduct.gradle.common.ViaductTenantTaskBase

/**
 * Task to generate tenant code for viaduct feature app tests.
 * This extends the common base class but is separate from ViaductTenantTask
 * to avoid conflicts when both viaduct-schema and viaduct-feature-app plugins
 * are used in the same build.
 */
abstract class ViaductFeatureAppTenantTask : ViaductTenantTaskBase() {
    @TaskAction
    fun generateFeatureAppTenant() {
        executeTenantGeneration()
    }
}
