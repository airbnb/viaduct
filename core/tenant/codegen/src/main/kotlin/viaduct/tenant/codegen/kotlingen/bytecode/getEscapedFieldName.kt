

private val TO_ESCAPE = setOf(
    // Keywords from https://kotlinlang.org/docs/keyword-reference.html#hard-keywords
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
) + setOf(
    // Special identifiers
    "_",
    "__",
    "___"
)

fun getEscapedFieldName(
    name: String,
    suffix: String = ""
): String =
    if (TO_ESCAPE.contains(name)) {
        "`$name$suffix`"
    } else {
        name + suffix
    }
