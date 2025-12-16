package viaduct.schema.base

interface BuilderBase<out T> {
    fun build(): T
}
