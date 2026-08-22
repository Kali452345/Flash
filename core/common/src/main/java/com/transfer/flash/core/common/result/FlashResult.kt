package com.transfer.flash.core.common.result

/**
 * Functional monadic result wrapper representing either a [Success] with value [T]
 * or a [Failure] with a typed [FlashError].
 */
sealed interface FlashResult<out T> {
    data class Success<out T>(val value: T) : FlashResult<T>
    data class Failure(val error: FlashError) : FlashResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    companion object {
        inline fun <T> runCatching(block: () -> T): FlashResult<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Failure(FlashError.Unknown(t.message ?: "Unknown exception occurred", t))
        }
    }
}

fun <T> FlashResult<T>.getOrNull(): T? = when (this) {
    is FlashResult.Success -> value
    is FlashResult.Failure -> null
}

inline fun <T> FlashResult<T>.getOrElse(default: (FlashError) -> T): T = when (this) {
    is FlashResult.Success -> value
    is FlashResult.Failure -> default(error)
}

inline fun <T, R> FlashResult<T>.map(transform: (T) -> R): FlashResult<R> = when (this) {
    is FlashResult.Success -> FlashResult.Success(transform(value))
    is FlashResult.Failure -> this
}

inline fun <T, R> FlashResult<T>.flatMap(transform: (T) -> FlashResult<R>): FlashResult<R> = when (this) {
    is FlashResult.Success -> transform(value)
    is FlashResult.Failure -> this
}

inline fun <T> FlashResult<T>.onSuccess(action: (T) -> Unit): FlashResult<T> {
    if (this is FlashResult.Success) action(value)
    return this
}

inline fun <T> FlashResult<T>.onFailure(action: (FlashError) -> Unit): FlashResult<T> {
    if (this is FlashResult.Failure) action(error)
    return this
}

inline fun <T, R> FlashResult<T>.fold(onSuccess: (T) -> R, onFailure: (FlashError) -> R): R = when (this) {
    is FlashResult.Success -> onSuccess(value)
    is FlashResult.Failure -> onFailure(error)
}
