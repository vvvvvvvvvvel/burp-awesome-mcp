package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.Cookie
import net.portswigger.mcp.history.SortOrder
import net.portswigger.mcp.history.previewValue
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class CookieJarService(
    private val api: MontoyaApi,
) {
    fun query(input: QueryCookieJarInput): QueryCookieJarResult {
        val normalizedLimit = input.limit.coerceIn(1, 500)
        val normalizedOffset = input.offset.coerceAtLeast(0)
        val maxValueChars = input.maxValueChars.coerceAtLeast(0)
        val now = ZonedDateTime.now()

        val domainPattern = compilePattern(input.domainRegex, "domainRegex")
        val namePattern = compilePattern(input.nameRegex, "nameRegex")

        val allCookies =
            api
                .http()
                .cookieJar()
                .cookies()
                .filter { cookie ->
                    val domain = cookie.domain()
                    val domainOk = domainPattern?.matcher(domain.orEmpty())?.find() ?: true
                    val nameOk = namePattern?.matcher(cookie.name())?.find() ?: true
                    val expired = cookie.expiration().orElse(null)?.isBefore(now) ?: false
                    val expirationOk = input.includeExpired || !expired
                    domainOk && nameOk && expirationOk
                }

        val sorted =
            allCookies.sortedWith(
                compareBy<Cookie>(
                    { it.domain().orEmpty() },
                    { it.path().orEmpty() },
                    { it.name() },
                    {
                        it
                            .expiration()
                            .orElse(null)
                            ?.toString()
                            .orEmpty()
                    },
                ),
            )

        val ordered =
            when (input.order) {
                SortOrder.ASC -> sorted
                SortOrder.DESC -> sorted.asReversed()
            }

        val selected = ordered.drop(normalizedOffset).take(normalizedLimit)
        val mapped =
            selected.map { cookie ->
                val fullValue = cookie.value()
                val valuePreview = previewValue(fullValue, maxChars = maxValueChars.coerceAtLeast(1))
                val value =
                    if (input.includeValues) {
                        if (maxValueChars == 0) "" else fullValue.take(maxValueChars)
                    } else {
                        null
                    }
                val truncated = input.includeValues && fullValue.length > maxValueChars

                CookieJarItem(
                    key = cookieKey(cookie),
                    name = cookie.name(),
                    domain = cookie.domain(),
                    path = cookie.path(),
                    expiration = cookie.expiration().orElse(null)?.toString(),
                    valuePreview = valuePreview,
                    value = value,
                    valueTruncated = truncated,
                )
            }

        return QueryCookieJarResult(
            total = allCookies.size,
            returned = mapped.size,
            offset = normalizedOffset,
            limit = normalizedLimit,
            hasMore = normalizedOffset + mapped.size < allCookies.size,
            order = input.order,
            domainRegex = input.domainRegex,
            nameRegex = input.nameRegex,
            includeExpired = input.includeExpired,
            includeValues = input.includeValues,
            maxValueChars = maxValueChars,
            results = mapped,
        )
    }

    fun setCookie(input: SetCookieJarCookieInput): SetCookieJarCookieResult {
        val maxValueChars = input.maxValueChars.coerceAtLeast(0)
        val parsedExpiration =
            input.expiration?.let {
                try {
                    ZonedDateTime.parse(it)
                } catch (_: Exception) {
                    throw IllegalArgumentException("expiration must be an ISO-8601 ZonedDateTime")
                }
            }

        api.http().cookieJar().setCookie(
            input.name,
            input.value,
            input.path,
            input.domain,
            parsedExpiration,
        )

        val keyMaterial = "${input.domain}\u0000${input.path.orEmpty()}\u0000${input.name}"
        val digest = MessageDigest.getInstance("SHA-256").digest(keyMaterial.toByteArray(Charsets.UTF_8))
        val key = digest.take(12).joinToString("") { byte -> "%02x".format(byte) }

        return SetCookieJarCookieResult(
            key = key,
            name = input.name,
            domain = input.domain,
            path = input.path,
            expiration = parsedExpiration?.toString(),
            valuePreview = previewValue(input.value, maxChars = maxValueChars.coerceAtLeast(1)),
            valueTruncated = input.value.length > maxValueChars,
        )
    }

    fun deleteCookie(input: DeleteCookieJarCookieInput): DeleteCookieJarCookieResult {
        val normalizedName = input.name.trim()
        val normalizedDomain = normalizeCookieDomain(input.domain)
        val normalizedPath = input.path?.trim()?.takeIf { it.isNotEmpty() }
        require(normalizedName.isNotEmpty()) { "name must not be blank" }
        require(normalizedDomain.isNotEmpty()) { "domain must not be blank" }

        val targets =
            api
                .http()
                .cookieJar()
                .cookies()
                .filter { cookie ->
                    val sameName = cookie.name().equals(normalizedName, ignoreCase = false)
                    val sameDomain =
                        normalizeCookieDomain(cookie.domain().orEmpty()).equals(normalizedDomain, ignoreCase = true)
                    val samePath =
                        normalizedPath?.let {
                            cookie.path().orEmpty() == it
                        } ?: true
                    sameName && sameDomain && samePath
                }

        val expiration = ZonedDateTime.now().minusYears(1)
        targets.forEach { cookie ->
            val targetDomain = cookie.domain()?.takeIf { it.isNotBlank() } ?: normalizedDomain
            api.http().cookieJar().setCookie(
                cookie.name(),
                "",
                cookie.path(),
                targetDomain,
                expiration,
            )
        }

        return DeleteCookieJarCookieResult(
            name = normalizedName,
            domain = normalizedDomain,
            path = normalizedPath,
            deleted = targets.size,
        )
    }

    private fun cookieKey(cookie: Cookie): String {
        val keyMaterial = "${cookie.domain().orEmpty()}\u0000${cookie.path().orEmpty()}\u0000${cookie.name()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(keyMaterial.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun compilePattern(
        regex: String?,
        field: String,
    ): Pattern? {
        if (regex.isNullOrBlank()) return null
        return try {
            Pattern.compile(regex)
        } catch (e: PatternSyntaxException) {
            throw IllegalArgumentException("Invalid $field: ${e.description}")
        }
    }

    private fun normalizeCookieDomain(domain: String): String = domain.trim().lowercase().removePrefix(".")
}
