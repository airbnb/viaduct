package viaduct.utils.string

import java.security.MessageDigest

/**
 * Extension function to compute the SHA-256 hash of a String.
 *
 * @return The SHA-256 hash of the String in hexadecimal format.
 */
fun String.sha256Hash(): String = toByteArray().sha256Hash()

/**
 * Extension function to compute the SHA-256 hash of a ByteArray.
 *
 * @return The SHA-256 hash of the ByteArray in hexadecimal format.
 */
fun ByteArray.sha256Hash(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .fold("") { str, it -> str + "%02x".format(it) }

/**
 * Extension function to capitalize a String in a non-deprecated way
 */
fun String.capitalize() = this.replaceFirstChar { if (it in 'a'..'z') it - 32 else it }

/**
 * Extension function to decapitalize a String in a non-deprecated way
 */
fun String.decapitalize() = this.replaceFirstChar { it.lowercase() }
