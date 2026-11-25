package viaduct.utils.resources

import java.text.Normalizer
import java.util.Locale
import viaduct.apiannotations.InternalApi

@InternalApi
object ViaductResourcePaths {
    const val FRAMEWORK = "viaduct.core"

    // Keep this producer key stable so execution manifest paths survive Gradle plugin renames.
    const val KOTLIN_GENERATOR = "com.airbnb.viaduct.module-gradle-plugin"
    const val JAVA_GENERATOR = "com.airbnb.viaduct.module-java-gradle-plugin"
    const val MANIFEST_ROOT = "viaduct/execution-manifest"

    private val artifacts = setOf("schema", "central-schema", "final-schema", "final-schema-contribution", "execution-manifest", "resource-index", "schema-scoping-manifest")
    private val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?")

    fun requireId(value: String) {
        require(value.matches(Regex("[A-Za-z0-9._-]+")) && value.any { it.isLetterOrDigit() }) { "Invalid Viaduct resource identity '$value'" }
        requireComponent(value)
    }

    private fun requireComponent(value: String) {
        require(
            value.isNotEmpty() && value != "." && value != ".." && !value.endsWith('.') && !value.endsWith(' ') &&
                value.none { Character.isISOControl(it) || it in "<>:\"\\|?*" } && !reserved.matches(value)
        ) {
            "Non-portable Viaduct resource path component '$value'"
        }
    }

    fun validate(path: String): String {
        val parts = path.split('/')
        parts.forEach(::requireComponent)
        require(parts.first() == "viaduct" && parts.getOrNull(1) in artifacts) { "Invalid canonical Viaduct resource path '$path'" }
        val origin = when (parts.getOrNull(2)) {
            "application" -> 3
            "module", "plugin" -> 4.also { requireId(parts.getOrElse(3) { "" }) }
            else -> throw IllegalArgumentException("Invalid Viaduct resource owner in '$path'")
        }
        val relative = when (parts.getOrNull(origin)) {
            "source" -> origin + 1
            "generated" -> (origin + 2).also { requireId(parts.getOrElse(origin + 1) { "" }) }
            else -> throw IllegalArgumentException("Invalid Viaduct resource origin in '$path'")
        }
        require(parts.size > relative) { "Missing relative filename in '$path'" }
        return path
    }

    fun normalized(path: String): String = Normalizer.normalize(validate(path), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun atStage(
        path: String,
        stage: String
    ): String {
        validate(path)
        return validate("viaduct/$stage/" + path.split('/').drop(2).joinToString("/"))
    }

    fun archive(path: String): String = "META-INF/${validate(path)}"

    fun fromArchive(path: String): String {
        require(path.startsWith("META-INF/")) { "Missing archive prefix in '$path'" }
        return validate(path.removePrefix("META-INF/"))
    }

    fun manifestPackage(path: String): String {
        validate(path)
        require(path.startsWith("$MANIFEST_ROOT/") && path.endsWith(".json")) { "Invalid execution manifest path '$path'" }
        return path.substringAfterLast('/').removeSuffix(".json")
    }

    fun matchesPackage(
        packageName: String,
        prefix: String?,
    ): Boolean = prefix == null || packageName == prefix || packageName.startsWith("$prefix.")

    fun manifest(
        moduleId: String,
        generator: String,
        filename: String
    ): String {
        requireId(moduleId)
        requireId(generator)
        return validate("$MANIFEST_ROOT/module/$moduleId/generated/$generator/$filename")
    }
}

@InternalApi
class ViaductResourceClaims {
    private val claims = linkedMapOf<String, Pair<String, String>>()

    fun clear() = claims.clear()

    fun claim(
        path: String,
        contributor: String
    ) {
        val previous = claims.putIfAbsent(ViaductResourcePaths.normalized(path), path to contributor)
        require(previous == null) { "Conflicting Viaduct resource '$path' from '$contributor' and '${previous?.first}' from '${previous?.second}'" }
    }
}
