package detekt

import org.jetbrains.kotlin.psi.KtModifierListOwner

// Matches both "StableApi" and "viaduct.apiannotations.StableApi" by comparing only the last segment.
internal fun KtModifierListOwner.hasAnnotation(simpleName: String): Boolean {
    val entries = annotationEntries
    if (entries.isEmpty()) return false
    return entries.any { entry ->
        val text = entry.typeReference?.text ?: return@any false
        val name = if (text.contains('.')) text.substringAfterLast('.') else text
        name == simpleName
    }
}

internal fun KtModifierListOwner.annotationsMatching(simpleNames: Set<String>): List<String> {
    return annotationEntries.mapNotNull { entry ->
        val text = entry.typeReference?.text ?: return@mapNotNull null
        val name = if (text.contains('.')) text.substringAfterLast('.') else text
        name.takeIf { it in simpleNames }
    }
}
