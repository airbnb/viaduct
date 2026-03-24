package viaduct.codegen.km

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmAnnotation
import kotlinx.metadata.KmConstructor
import kotlinx.metadata.KmType
import kotlinx.metadata.KmTypeProjection
import kotlinx.metadata.KmVariance
import kotlinx.metadata.Visibility
import kotlinx.metadata.jvm.JvmMethodSignature
import kotlinx.metadata.jvm.signature
import kotlinx.metadata.kind
import kotlinx.metadata.visibility
import viaduct.codegen.ct.KmClassTree
import viaduct.codegen.utils.JavaIdName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName

class EnumClassBuilder internal constructor(
    kmName: KmName,
    values: List<String>,
    tier: Int = 1,
) : ClassBuilder() {
    private val builder = CustomClassBuilder(
        kmKind = ClassKind.ENUM_CLASS,
        kmName = kmName,
        tier = tier
    )

    init {
        builder.addEnumEntry(*values.toTypedArray())

        builder.addConstructor(
            KmConstructor().apply {
                visibility = Visibility.PRIVATE
                signature = JvmMethodSignature("<init>", "(Ljava/lang/String;I)V")
            }
        )

        builder.addSupertype(
            Km.ENUM.asType().apply {
                arguments.add(
                    KmTypeProjection(
                        variance = KmVariance.INVARIANT,
                        type = kmName.asType()
                    )
                )
            }
        )
    }

    fun addSupertype(kmType: KmType): EnumClassBuilder = this.also { builder.addSupertype(kmType) }

    fun nestedClassBuilder(
        simpleName: JavaIdName,
        annotations: Set<Pair<KmAnnotation, Boolean>> = emptySet(),
        kind: ClassKind = ClassKind.CLASS
    ): CustomClassBuilder = builder.nestedClassBuilder(simpleName, annotations, kind)

    override fun build(): KmClassTree = builder.build()
}
