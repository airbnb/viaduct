package viaduct.codegen.km

import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.codegen.utils.KmName

class EnumGenTest {
    @Test
    fun testEnum() {
        assertEquals(
            "SUNNY",
            weatherTypeClass.getMethod("valueOf", String::class.java).invoke(null, "SUNNY").toString()
        )
        assertEquals(
            "CLOUDY",
            weatherTypeClass.getMethod("valueOf", String::class.java).invoke(null, "CLOUDY").toString()
        )
        assertEquals(
            listOf("CLOUDY", "SUNNY", "THUNDER_STORM"),
            (weatherTypeClass.getMethod("values").invoke(null) as Array<*>).map { it.toString() }
        )
    }

    @Test
    fun testJavaKeywordEnumValues() {
        val values = (keywordTypeClass.getMethod("values").invoke(null) as Array<*>).map { it as Enum<*> }
        assertEquals(
            listOf("class" to 0, "if" to 1, "new" to 2, "SUNNY" to 3),
            values.map { it.name to it.ordinal }
        )
        assertEquals(
            "if",
            keywordTypeClass.getMethod("valueOf", String::class.java).invoke(null, "if").toString()
        )
    }

    @Test
    fun testEnumWithNoValues() {
        assertEquals(
            emptyList<Any?>(),
            (emptyTypeClass.getMethod("values").invoke(null) as Array<*>).toList()
        )
    }

    @Test
    fun testInvalidValueOf() {
        val exception =
            assertThrows(
                InvocationTargetException::class.java,
                Executable {
                    weatherTypeClass.getMethod("valueOf", String::class.java).invoke(null, "CHANCE_OF_MEATBALLS")
                }
            )
        val targetException = exception.targetException
        assertTrue(targetException is IllegalArgumentException)
        assertEquals(
            "No enum constant WeatherType.CHANCE_OF_MEATBALLS",
            targetException.message
        )
    }

    companion object {
        private const val CLASS_NAME: String = "WeatherType" // A simple name, which means it qualifies for all names
        private const val KEYWORD_CLASS_NAME: String = "KeywordEnum"
        private const val EMPTY_CLASS_NAME: String = "EmptyEnum"
        private lateinit var weatherTypeClass: Class<*>
        private lateinit var keywordTypeClass: Class<*>
        private lateinit var emptyTypeClass: Class<*>

        @BeforeAll
        @JvmStatic
        fun setup() {
            val kmCtx = KmClassFilesBuilder()
            kmCtx.enumClassBuilder(KmName(CLASS_NAME), listOf("CLOUDY", "SUNNY", "THUNDER_STORM"))
            // Java keywords are legal GraphQL enum values and legal JVM field names.
            kmCtx.enumClassBuilder(KmName(KEYWORD_CLASS_NAME), listOf("class", "if", "new", "SUNNY"))
            kmCtx.enumClassBuilder(KmName(EMPTY_CLASS_NAME), emptyList())
            val classLoader = kmCtx.buildClassLoader()
            weatherTypeClass = classLoader.loadClass(CLASS_NAME)
            keywordTypeClass = classLoader.loadClass(KEYWORD_CLASS_NAME)
            emptyTypeClass = classLoader.loadClass(EMPTY_CLASS_NAME)
        }
    }
}
