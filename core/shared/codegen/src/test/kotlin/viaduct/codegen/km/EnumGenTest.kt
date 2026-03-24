package viaduct.codegen.km

import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import viaduct.codegen.utils.JavaBinaryName
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
        private lateinit var weatherTypeClass: Class<*>

        @BeforeAll
        @JvmStatic
        fun setup() {
            val kmCtx = KmClassFilesBuilder()
            kmCtx.enumClassBuilder(KmName(CLASS_NAME), listOf("CLOUDY", "SUNNY", "THUNDER_STORM"))
            weatherTypeClass = kmCtx.loadClass(JavaBinaryName(CLASS_NAME))
        }
    }
}
