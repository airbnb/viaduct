package viaduct.api.internal

import viaduct.apiannotations.StableApi

@StableApi
interface InputValueBuilder<T> {
    fun build(): T
}
