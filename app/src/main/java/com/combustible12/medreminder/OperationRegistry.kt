package com.combustible12.medreminder

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf

/**
 * Tracks work by operation identity. Saving one editor never blocks another editor,
 * while a repeated tap on the same action cannot start a duplicate database write.
 */
@Stable
internal class OperationRegistry {
    private val active = mutableStateMapOf<String, Boolean>()

    fun isRunning(key: String): Boolean = active[key] == true

    suspend fun run(key: String, action: suspend () -> Unit) {
        check(active.put(key, true) != true) { "该操作正在处理，请稍后" }
        try {
            action()
        } finally {
            active.remove(key)
        }
    }
}
