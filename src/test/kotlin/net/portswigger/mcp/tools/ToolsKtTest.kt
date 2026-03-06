package net.portswigger.mcp.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolsKtTest {
    @Test
    fun `executeBulk should preserve order in sequential mode`() =
        runBlocking {
            val result =
                executeBulk(
                    items = listOf(1, 2, 3),
                    parallel = false,
                    parallelRps = 10,
                ) {
                    "item-$it"
                }

            assertEquals(listOf("item-1", "item-2", "item-3"), result)
        }

    @Test
    fun `executeBulk should preserve order in parallel mode`() =
        runBlocking {
            val result =
                executeBulk(
                    items = listOf(1, 2, 3),
                    parallel = true,
                    parallelRps = 10,
                ) {
                    delay(10)
                    "item-$it"
                }

            assertEquals(listOf("item-1", "item-2", "item-3"), result)
        }

    @Test
    fun `executeBulk should apply rate limit in parallel mode`() =
        runBlocking {
            val start = System.currentTimeMillis()

            executeBulk(
                items = listOf(1, 2, 3),
                parallel = true,
                parallelRps = 5,
            ) {
                it
            }

            val elapsed = System.currentTimeMillis() - start
            assertTrue(elapsed >= 350, "elapsed=$elapsed ms should reflect rate limiting")
        }
}
