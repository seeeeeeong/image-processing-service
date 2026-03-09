package com.realteeth.imagejob.config

import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException
import java.util.function.Predicate

class RetryableExceptionPredicate : Predicate<Throwable> {

    override fun test(t: Throwable): Boolean {
        return isRetryable(t) || (t.cause != null && isRetryable(t.cause!!))
    }

    private fun isRetryable(t: Throwable): Boolean = when (t) {
        is WebClientResponseException ->
            t.statusCode.is5xxServerError || t.statusCode == HttpStatus.TOO_MANY_REQUESTS
        is IOException -> true
        else -> t.javaClass.name.contains("TimeoutException") ||
                t.javaClass.name.contains("ReadTimeoutException")
    }
}
