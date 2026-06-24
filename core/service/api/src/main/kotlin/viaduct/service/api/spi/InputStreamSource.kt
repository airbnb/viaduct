package viaduct.service.api.spi

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import viaduct.apiannotations.StableApi

@StableApi
fun interface InputStreamSource {
    @Throws(IOException::class)
    fun openStream(): InputStream

    companion object {
        @JvmStatic
        fun fromUrl(url: URL): InputStreamSource =
            object : InputStreamSource {
                override fun openStream(): InputStream = url.openStream()

                override fun toString(): String = "InputStreamSource from URL '$url'"
            }

        @JvmStatic
        fun fromFile(file: File): InputStreamSource =
            object : InputStreamSource {
                override fun openStream(): InputStream = file.inputStream()

                override fun toString(): String = "InputStreamSource from file '$file'"
            }

        @JvmStatic
        @JvmOverloads
        fun fromString(
            content: String,
            /** A name for this source to be used by `toString` so this content can be identified in debugging situations. */
            name: String,
            charset: Charset = StandardCharsets.UTF_8,
        ): InputStreamSource =
            object : InputStreamSource {
                override fun openStream(): InputStream = ByteArrayInputStream(content.toByteArray(charset))

                override fun toString(): String = "InputStreamSource from string named '$name'"
            }
    }
}
