package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.scanner.AuditConfiguration
import burp.api.montoya.scanner.BuiltInAuditConfiguration
import burp.api.montoya.scanner.Crawl
import burp.api.montoya.scanner.CrawlConfiguration
import burp.api.montoya.scanner.Scanner
import burp.api.montoya.scanner.audit.Audit
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScannerTaskServiceTest {
    private val api = mockk<MontoyaApi>()
    private val scanner = mockk<Scanner>()

    @Test
    fun `list should return empty result when no tracked tasks`() {
        val service = ScannerTaskService(api)
        val listed = service.list()
        assertEquals(0, listed.total)
        assertEquals(emptyList<ScannerTaskStatusResult>(), listed.results)
    }

    @Test
    fun `startAudit should delete and untrack task when addRequest fails`() {
        val task = mockk<Audit>()
        val request = mockk<HttpRequest>()
        val config = mockk<AuditConfiguration>()
        mockkStatic(AuditConfiguration::class)
        mockkStatic(HttpRequest::class)
        every { api.scanner() } returns scanner
        every { AuditConfiguration.auditConfiguration(any<BuiltInAuditConfiguration>()) } returns config
        every { HttpRequest.httpRequestFromUrl(any()) } returns request
        every { scanner.startAudit(any()) } returns task
        every { task.addRequest(request) } throws IllegalStateException("boom")
        every { task.delete() } just runs

        val service = ScannerTaskService(api)

        try {
            assertThrows(IllegalStateException::class.java) {
                service.startAudit(
                    preset = ScannerAuditPreset.ACTIVE_AUDIT_CHECKS,
                    urls = listOf("example.com"),
                )
            }
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(AuditConfiguration::class)
        }

        verify(exactly = 1) { task.delete() }
        val unknown =
            assertThrows(IllegalArgumentException::class.java) {
                service.status("audit-1")
            }
        assertEquals("unknown scanner task_id 'audit-1'", unknown.message)
    }

    @Test
    fun `trimOld should delete evicted tracked tasks`() {
        val crawlConfig = mockk<CrawlConfiguration>()
        mockkStatic(CrawlConfiguration::class)
        every { api.scanner() } returns scanner
        every { CrawlConfiguration.crawlConfiguration(*anyVararg()) } returns crawlConfig
        every { scanner.startCrawl(any()) } answers { mockk<Crawl>(relaxed = true) }

        try {
            val service = ScannerTaskService(api)
            repeat(257) {
                service.startCrawl(listOf("https://example.com/$it"))
            }

            val unknown =
                assertThrows(IllegalArgumentException::class.java) {
                    service.status("crawl-1")
                }
            assertEquals("unknown scanner task_id 'crawl-1'", unknown.message)
        } finally {
            unmockkStatic(CrawlConfiguration::class)
        }
    }
}
