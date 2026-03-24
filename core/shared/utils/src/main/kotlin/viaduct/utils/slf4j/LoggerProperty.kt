package viaduct.utils.slf4j

import org.slf4j.LoggerFactory

/**
 * Usage : companion object { private val log by logger() }
 */
inline fun <reified R : Any> R.logger() =
    lazy {
        LoggerFactory.getLogger(this::class.java.name.substringBefore("\$Companion"))!!
    }
