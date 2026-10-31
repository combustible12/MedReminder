package com.combustible12.medreminder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OperationRegistryTest {
    @Test fun sameOperationIsSingleFlightWhileDifferentEditorsStayIndependent() = runBlocking {
        val registry = OperationRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            registry.run("medicine:1:save") {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        assertTrue(registry.isRunning("medicine:1:save"))
        assertFalse(registry.isRunning("medicine:2:save"))

        val duplicate = runCatching { registry.run("medicine:1:save") {} }
        assertTrue(duplicate.isFailure)

        registry.run("medicine:2:save") {}
        assertFalse(registry.isRunning("medicine:2:save"))

        release.complete(Unit)
        first.await()
        assertFalse(registry.isRunning("medicine:1:save"))
    }
}
