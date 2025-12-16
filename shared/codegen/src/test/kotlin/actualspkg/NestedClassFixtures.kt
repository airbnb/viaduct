package actualspkg

interface Outer {
    interface Inner
}

interface IA_1 {
    interface IA_2
}

interface IB_1 {
    interface IB_2
}

interface IC_1 {
    interface IC_2
}

interface ID_1 : IC_1 {
    interface ID_2 : IC_1.IC_2
}

interface InterfaceWithDefaults1 {
    fun f(): Int = 1
}

interface InterfaceWithDefaults2 {
    fun g(): Int = 2
}

annotation class AOuter(
    val inner: AInner
) {
    annotation class AInner
}
