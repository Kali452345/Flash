package com.transfer.flash.core.common.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Protocol serialization and text escaping utilities for Flash line-based TCP and WebSocket framing.
 *
 * Escapes delimiters:
 * - '%' -> '%25'
 * - ' ' (space) -> '%20'
 * - '=' -> '%3D'
 */
@FlashInternalApi
object FlashTextFraming {

    fun escape(value: String): String {
        return value
            .replace("%", "%25")
            .replace(" ", "%20")
            .replace("=", "%3D")
    }

    fun unescape(value: String): String {
        return value
            .replace("%3D", "=")
            .replace("%20", " ")
            .replace("%25", "%")
    }

    fun encodeFields(prefix: String, vararg fields: Pair<String, String>): String {
        return encodeFields(prefix, fields.toList())
    }

    fun encodeFields(prefix: String, fields: List<Pair<String, String>>): String {
        val encodedPairs = fields.map { (key, value) ->
            "$key=${escape(value)}"
        }
        return (listOf(prefix) + encodedPairs).joinToString(separator = " ")
    }

    fun parseFields(text: String, expectedPrefix: String): Map<String, String>? {
        val parts = text.trim().split(' ')
        if (parts.firstOrNull() != expectedPrefix) return null

        return parts.drop(1)
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    val key = part.substring(0, separator)
                    val rawValue = part.substring(separator + 1)
                    key to unescape(rawValue)
                }
            }
            .toMap()
    }
}
