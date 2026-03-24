package actualspkg

interface Iface<T> {
    fun read(): T

    fun write(t: T): Boolean
}
