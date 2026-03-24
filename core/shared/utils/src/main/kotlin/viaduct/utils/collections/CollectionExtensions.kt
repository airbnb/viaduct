package viaduct.utils.collections

fun <E> ArrayList<E>.expandWith(
    size: Int,
    value: E
) {
    if (this.size >= size) return
    this.ensureCapacity(size)
    repeat(size - this.size) {
        this.add(value)
    }
}
