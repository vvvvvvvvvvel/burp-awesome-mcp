package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.scanner.AuditConfiguration
import burp.api.montoya.scanner.BuiltInAuditConfiguration
import burp.api.montoya.scanner.CrawlConfiguration
import burp.api.montoya.scanner.ReportFormat
import burp.api.montoya.scanner.ScanTask
import burp.api.montoya.scanner.audit.issues.AuditIssue
import java.nio.file.Path
import java.util.LinkedHashMap

class ScannerTaskService(
    private val api: MontoyaApi,
) {
    private val lock = Any()
    private var seq = 0L
    private val tasks = LinkedHashMap<String, TrackedScanTask>()

    fun startCrawl(seedUrls: List<String>): ScannerTaskStatusResult {
        require(seedUrls.isNotEmpty()) { "seed_urls must not be empty" }
        val sanitized = seedUrls.map(::normalizeUrl)
        val task = api.scanner().startCrawl(CrawlConfiguration.crawlConfiguration(*sanitized.toTypedArray()))
        val taskId = store(task, "crawl")
        return status(taskId)
    }

    fun startAudit(
        preset: ScannerAuditPreset,
        urls: List<String>,
    ): ScannerTaskStatusResult {
        val normalizedUrls = urls.map(::normalizeUrl)
        val builtIn =
            when (preset) {
                ScannerAuditPreset.PASSIVE_AUDIT_CHECKS -> BuiltInAuditConfiguration.LEGACY_PASSIVE_AUDIT_CHECKS
                ScannerAuditPreset.ACTIVE_AUDIT_CHECKS -> BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS
            }
        val audit = api.scanner().startAudit(AuditConfiguration.auditConfiguration(builtIn))
        val taskId = store(audit, "audit")
        try {
            normalizedUrls.forEach { url ->
                audit.addRequest(HttpRequest.httpRequestFromUrl(url))
            }
        } catch (e: Exception) {
            runCatching { audit.delete() }
            synchronized(lock) {
                tasks.remove(taskId)
            }
            throw e
        }
        return status(taskId)
    }

    fun status(taskId: String): ScannerTaskStatusResult {
        val tracked = getTracked(taskId)
        return toStatusResult(taskId, tracked)
    }

    fun list(): ListScannerTasksResult {
        val snapshot = synchronized(lock) { tasks.toList() }
        val results = snapshot.map { (taskId, tracked) -> toStatusResult(taskId, tracked) }
        return ListScannerTasksResult(
            total = results.size,
            results = results,
        )
    }

    fun cancel(taskId: String): CancelScannerTaskResult {
        val tracked = getTracked(taskId)
        tracked.task.delete()
        synchronized(lock) {
            tasks.remove(taskId)
        }
        return CancelScannerTaskResult(taskId = taskId, deleted = true)
    }

    fun generateReport(
        issues: List<AuditIssue>,
        format: ScannerReportFormatInput,
        outputFile: String,
    ): GenerateScannerReportResult {
        val reportFormat =
            when (format) {
                ScannerReportFormatInput.HTML -> ReportFormat.HTML
                ScannerReportFormatInput.XML -> ReportFormat.XML
            }
        val outputPath = Path.of(outputFile)
        api.scanner().generateReport(issues, reportFormat, outputPath)
        return GenerateScannerReportResult(
            outputFile = outputPath.toAbsolutePath().toString(),
            format = format.name.lowercase(),
            includedIssues = issues.size,
        )
    }

    @Suppress("HttpUrlsUsage")
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotBlank()) { "url must not be blank" }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun store(
        task: ScanTask,
        taskType: String,
    ): String =
        synchronized(lock) {
            val id = "$taskType-${++seq}"
            tasks[id] = TrackedScanTask(taskType = taskType, task = task)
            trimOld()
            id
        }

    private fun trimOld() {
        while (tasks.size > MAX_TRACKED_SCAN_TASKS) {
            val firstEntry = tasks.entries.firstOrNull() ?: break
            tasks.remove(firstEntry.key)
            runCatching { firstEntry.value.task.delete() }
        }
    }

    private fun getTracked(taskId: String): TrackedScanTask =
        synchronized(lock) {
            tasks[taskId] ?: throw IllegalArgumentException("unknown scanner task_id '$taskId'")
        }

    private fun toStatusResult(
        taskId: String,
        tracked: TrackedScanTask,
    ): ScannerTaskStatusResult =
        ScannerTaskStatusResult(
            taskId = taskId,
            taskType = tracked.taskType,
            statusMessage = runCatching { tracked.task.statusMessage() }.getOrDefault("unknown"),
            requestCount = runCatching { tracked.task.requestCount() }.getOrDefault(0),
            errorCount = runCatching { tracked.task.errorCount() }.getOrDefault(0),
        )
}

private data class TrackedScanTask(
    val taskType: String,
    val task: ScanTask,
)

private const val MAX_TRACKED_SCAN_TASKS = 256
