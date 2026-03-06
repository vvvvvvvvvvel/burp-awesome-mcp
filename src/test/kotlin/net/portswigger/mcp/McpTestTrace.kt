package net.portswigger.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

object McpTestTrace {
    private val tracePath: Path = Path.of("test-artifacts", "mcp-integration-trace.log")
    private val initialized = AtomicBoolean(false)
    private val lock = Any()

    fun log(
        scope: String,
        event: String,
        payload: Any? = null,
    ) {
        ensureInitialized()
        val line =
            buildString {
                append(Instant.now().toString())
                append(" [")
                append(scope)
                append("] ")
                append(event)
                if (payload != null) {
                    append(" :: ")
                    append(compact(payload.toString()))
                }
            }

        synchronized(lock) {
            Files.writeString(
                tracePath,
                line + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }

        println(line)
    }

    private fun ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) return
        synchronized(lock) {
            Files.createDirectories(tracePath.parent)
            Files.writeString(
                tracePath,
                "",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
    }
}

private fun compact(
    input: String,
    limit: Int = 2500,
): String {
    val normalized =
        input
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    if (normalized.length <= limit) return normalized
    return normalized.take(limit - 3) + "..."
}
