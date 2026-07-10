import org.gradle.api.GradleException
import org.gradle.api.initialization.Settings
import viaduct.gradle.IncludeViaductApplicationHandler
import viaduct.gradle.ViaductApplicationSpec

fun Settings.includeViaductApplication(configure: ViaductApplicationSpec.() -> Unit) {
    val handler = extensions.findByType(IncludeViaductApplicationHandler::class.java)
        ?: throw GradleException(
            "Apply 'com.airbnb.viaduct.settings-gradle-plugin' before calling includeViaductApplication { ... }.",
        )
    handler.include(configure)
}
