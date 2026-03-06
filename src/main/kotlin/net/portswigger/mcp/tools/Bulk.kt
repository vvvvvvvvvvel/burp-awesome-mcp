package net.portswigger.mcp.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

suspend fun <I, O> executeBulk(
    items: List<I>,
    parallel: Boolean,
    parallelRps: Int,
    block: suspend (I) -> O,
): List<O> {
    if (!parallel || items.size <= 1) {
        return items.map { block(it) }
    }

    val safeRps = parallelRps.coerceAtLeast(1)
    val intervalMs = (1000.0 / safeRps).toLong().coerceAtLeast(1)

    return coroutineScope {
        items
            .mapIndexed { index, item ->
                async {
                    delay(index * intervalMs)
                    withContext(Dispatchers.IO) {
                        block(item)
                    }
                }
            }.awaitAll()
    }
}
