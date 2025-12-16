@file:Suppress("UNUSED", "UNUSED_PARAMETER", "UNUSED_VARIABLE")

package expectedspkg

interface Outer {
    interface Inner {
        interface Center
    }
}

interface InterfaceWithNestedClass {
    class NestedClass
}

class DeeplyNestedClasses {
    class N1 {
        class N2 {
            class N3

            class M3
        }

        class M2
    }
}

interface IA_1 {
    interface IA_2
}

interface IB_1 {
    interface IB_2
}

class MultipleNestedInheritance {
    class N1 :
        IA_1,
        IB_1 {
        class N2 :
            IA_1.IA_2,
            IB_1.IB_2
    }
}

class ClassWithNestAndReference {
    class N

    fun n(): N = TODO()
}

class ClassWithNestAndReference2 {
    class A {
        class B {
            fun a(): A = A()
        }
    }
}

interface IC_1 {
    interface IC_2
}

interface ID_1 : IC_1 {
    interface ID_2 : IC_1.IC_2
}

class TransitiveNestedInheritance {
    class N1 : ID_1 {
        class N2 : ID_1.ID_2
    }
}

class HasNestedProp {
    val x: Outer.Inner = TODO()
}

class HasNestedTypeParam<T : Outer.Inner>

class HasNestedCtorParam(
    x: Outer.Inner
)

class HasNestedMethodParam {
    fun f(x: Outer.Inner) = Unit
}

class HasNestedMethodReturn {
    fun f(): Outer.Inner = TODO()
}

class HasMethodNestedRef {
    fun f() {
        val x: Outer.Inner
    }
}

class HasPropertyNestedRef {
    val prop: Any get() = Outer.Inner::class
}

class HasCtorNestedRef {
    init {
        val x: Outer.Inner
    }
}

interface InterfaceWithDefaults1 {
    fun f(): Int = 1
}

interface InterfaceWithDefaults2 {
    fun g(): Int = 2
}

class ClassExtendsInterfaceWithDefaults : InterfaceWithDefaults1

class ClassExtendsInterfaceWithDefaults2 :
    InterfaceWithDefaults1,
    InterfaceWithDefaults2

interface InterfaceExtendsInterfaceWithDefaults : InterfaceWithDefaults1

interface InterfaceExtendsInterfaceWithDefaults2 : InterfaceWithDefaults1 {
    override fun f(): Int = 2
}

interface InterfaceExtendsInterfaceWithDefaults3 : InterfaceWithDefaults1 {
    override fun f(): Int = super.f()
}

@Target(
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
    AnnotationTarget.FILE,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.TYPE_PARAMETER,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class AOuter(
    val inner: AInner
) {
    annotation class AInner
}

@AOuter(AOuter.AInner())
class HasNestedClassAnnotation

@Suppress("ktlint:standard:indent")
class HasNestedCtorAnnotation
    @AOuter(AOuter.AInner())
    constructor()

class HasNestedCtorParamAnnotation(
    @AOuter(AOuter.AInner()) x: Int
)

class HasNestedTypeParamAnnotation<
    @Suppress("ktlint:standard:indent", "ktlint:standard:annotation-spacing")
    @AOuter(AOuter.AInner())
    T
>

class HasNestedMethodAnnotation {
    @AOuter(AOuter.AInner())
    fun x() = Unit
}

class HasNestedFieldAnnotation {
    @field:AOuter(AOuter.AInner())
    val x: Int = 0
}

class HasNestedPropertyGetterAnnotation {
    @get:AOuter(AOuter.AInner())
    val x: Int get() = 0
}

class HasNestedPropertySetterAnnotation {
    @set:AOuter(AOuter.AInner())
    var x: Int
        get() = 0
        set(value) {}
}

enum class EnumHasNestedClass {
    Value;

    class Nested
}
