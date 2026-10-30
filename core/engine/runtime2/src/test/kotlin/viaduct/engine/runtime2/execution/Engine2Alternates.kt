package viaduct.engine.runtime2.execution

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

enum class Engine2DisabledKind { TODO, NA, ALT }

@Test
@Disabled
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Engine2Disabled(val kind: Engine2DisabledKind, val reason: String)

@Test
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Engine2Alternate(val original: String)

internal data class AlternateRef(val name: String, val original: String)

internal data class AlternateMarks(
    val disabled: Map<String, Engine2DisabledKind>,
    val alternates: List<AlternateRef>,
) {
    val disabledAlt: List<String> get() = disabled.filterValues { it == Engine2DisabledKind.ALT }.keys.toList()
}

internal fun alternatePairingViolations(marks: AlternateMarks): List<String> {
    val unpaired = marks.disabledAlt.mapNotNull { original ->
        val count = marks.alternates.count { it.original == original }
        "ALT original '$original' has $count alternates, expected 1".takeIf { count != 1 }
    }
    val orphaned = marks.alternates
        .filter { it.original !in marks.disabled }
        .map { "alternate '${it.name}' targets '${it.original}', which is not a disabled test" }
    return unpaired + orphaned
}

internal fun collectAlternateMarks(root: Class<*>): AlternateMarks {
    val methods = generateSequence(listOf(root)) { level -> level.flatMap { it.declaredClasses.toList() }.ifEmpty { null } }
        .flatten()
        .flatMap { generateSequence(it) { c -> c.superclass } }
        .distinct()
        .flatMap { it.declaredMethods.asSequence() }
        .toList()
    return AlternateMarks(
        disabled = methods
            .mapNotNull { method -> method.getAnnotation(Engine2Disabled::class.java)?.let { method.name to it.kind } }
            .toMap(),
        alternates = methods.mapNotNull { method ->
            method.getAnnotation(Engine2Alternate::class.java)?.let { AlternateRef(method.name, it.original) }
        },
    )
}
