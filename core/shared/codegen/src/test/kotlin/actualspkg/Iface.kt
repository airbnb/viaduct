package actualspkg

interface Iface<T> {
    fun read(): T

    fun write(t: T): Boolean
}

interface CovariantRead {
    fun read(): CharSequence
}

interface StringRead {
    fun read(): String
}
