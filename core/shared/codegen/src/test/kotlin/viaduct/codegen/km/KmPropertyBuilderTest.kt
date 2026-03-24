package viaduct.codegen.km

import kotlinx.metadata.KmType
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.modality
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km

class KmPropertyBuilderTest {
    private fun mk(
        name: String = "foo",
        type: KmType = Km.STRING.asType(),
        inputType: KmType = Km.STRING.asType(),
        isVariable: Boolean = false,
        constructorProperty: Boolean = false
    ): KmPropertyBuilder = KmPropertyBuilder(JavaIdName(name), type, inputType, isVariable, constructorProperty)

    @Test
    fun `validates getter visibility`() {
        Visibility.values().forEach { vis ->
            val result =
                mk().let { builder ->
                    Result.runCatching { builder.getterVisibility(vis) }
                }

            if (vis == Visibility.INTERNAL) {
                assert(result.exceptionOrNull() is IllegalArgumentException)
            } else {
                assert(result.isSuccess)
            }
        }
    }

    @Test
    fun `validates setter visibility`() {
        Visibility.values().forEach { vis ->
            val result =
                mk(isVariable = true).let { builder ->
                    Result.runCatching { builder.setterVisibility(vis) }
                }

            if (vis == Visibility.INTERNAL) {
                assert(result.exceptionOrNull() is IllegalArgumentException)
            } else {
                assert(result.isSuccess)
            }
        }
    }

    @Test
    fun `validates modality`() {
        val propertyWrapper = mk()
            .propertyModality(Modality.OPEN)
            .build()
        assertEquals(Modality.OPEN, propertyWrapper.property.modality)
        assertEquals(Modality.OPEN, propertyWrapper.getter.function.modality)
    }
}
