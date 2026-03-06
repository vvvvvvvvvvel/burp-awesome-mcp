package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.Http
import burp.api.montoya.http.message.Cookie
import burp.api.montoya.http.sessions.CookieJar
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import net.portswigger.mcp.history.SortOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.util.Optional

class CookieJarServiceTest {
    private lateinit var api: MontoyaApi
    private lateinit var http: Http
    private lateinit var cookieJar: CookieJar
    private lateinit var service: CookieJarService

    @BeforeEach
    fun setup() {
        api = mockk()
        http = mockk()
        cookieJar = mockk()
        service = CookieJarService(api)

        every { api.http() } returns http
        every { http.cookieJar() } returns cookieJar
    }

    @Test
    fun `query should paginate and filter cookie jar`() {
        val now = ZonedDateTime.now()
        every { cookieJar.cookies() } returns
            listOf(
                mockCookie("sid", "abc123", "app.example.com", "/", null),
                mockCookie("csrf", "token-1", "app.example.com", "/", null),
                mockCookie("auth", "Bearer long-secret-token", "api.example.com", "/v1", null),
                mockCookie("old", "x", "api.example.com", "/v1", now.minusDays(1)),
            )

        val result =
            service.query(
                QueryCookieJarInput(
                    limit = 1,
                    offset = 0,
                    order = SortOrder.DESC,
                    domainRegex = "api\\.",
                    includeValues = true,
                    maxValueChars = 6,
                ),
            )

        assertEquals(1, result.total)
        assertEquals(1, result.returned)
        assertEquals(true, result.hasMore.not())
        assertEquals("auth", result.results.first().name)
        assertEquals("Bearer", result.results.first().value)
        assertEquals(true, result.results.first().valueTruncated)
    }

    @Test
    fun `query should hide expired cookies by default and return them when requested`() {
        val now = ZonedDateTime.now()
        every { cookieJar.cookies() } returns
            listOf(
                mockCookie("active", "1", "example.com", "/", now.plusDays(2)),
                mockCookie("expired", "2", "example.com", "/", now.minusDays(2)),
            )

        val hiddenExpired = service.query(QueryCookieJarInput(domainRegex = "example\\.com"))
        assertEquals(1, hiddenExpired.total)
        assertEquals("active", hiddenExpired.results.first().name)

        val withExpired = service.query(QueryCookieJarInput(domainRegex = "example\\.com", includeExpired = true))
        assertEquals(2, withExpired.total)
    }

    @Test
    fun `query should fail on invalid regex`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.query(QueryCookieJarInput(domainRegex = "[bad"))
        }
    }

    @Test
    fun `set cookie should call montoya cookie jar and parse expiration`() {
        every { cookieJar.setCookie(any(), any(), any(), any(), any()) } just runs

        val result =
            service.setCookie(
                SetCookieJarCookieInput(
                    name = "session",
                    value = "xyz",
                    domain = "example.com",
                    path = "/",
                    expiration = "2026-12-31T23:59:59Z",
                    maxValueChars = 20,
                ),
            )

        assertEquals("session", result.name)
        assertEquals("example.com", result.domain)
        assertEquals("/", result.path)
        verify(exactly = 1) {
            cookieJar.setCookie(
                "session",
                "xyz",
                "/",
                "example.com",
                ZonedDateTime.parse("2026-12-31T23:59:59Z"),
            )
        }
    }

    @Test
    fun `set cookie should fail on invalid expiration`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.setCookie(
                SetCookieJarCookieInput(
                    name = "session",
                    value = "xyz",
                    domain = "example.com",
                    expiration = "not-a-date",
                ),
            )
        }
    }

    @Test
    fun `delete cookie should expire matching cookie jar entries`() {
        val cookieOne = mockCookie("session", "a", "example.com", "/", null)
        val cookieTwo = mockCookie("session", "b", "example.com", "/api", null)
        val cookieOther = mockCookie("other", "c", "example.com", "/", null)
        every { cookieJar.cookies() } returns listOf(cookieOne, cookieTwo, cookieOther)
        every { cookieJar.setCookie(any(), any(), any(), any(), any()) } just runs

        val result =
            service.deleteCookie(
                DeleteCookieJarCookieInput(
                    name = "session",
                    domain = "example.com",
                ),
            )

        assertEquals(2, result.deleted)
        verify(exactly = 2) {
            cookieJar.setCookie(
                "session",
                "",
                any(),
                "example.com",
                any(),
            )
        }
    }

    @Test
    fun `delete cookie should not create tombstone when no cookie matched`() {
        val cookieOne = mockCookie("session", "a", "example.com", "/", null)
        every { cookieJar.cookies() } returns listOf(cookieOne)
        every { cookieJar.setCookie(any(), any(), any(), any(), any()) } just runs

        val result =
            service.deleteCookie(
                DeleteCookieJarCookieInput(
                    name = "session",
                    domain = "example.com",
                    path = "/missing",
                ),
            )

        assertEquals(0, result.deleted)
        verify(exactly = 0) {
            cookieJar.setCookie(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `delete cookie should match domain with and without leading dot`() {
        val dotted = mockCookie("session", "a", ".example.com", "/", null)
        every { cookieJar.cookies() } returns listOf(dotted)
        every { cookieJar.setCookie(any(), any(), any(), any(), any()) } just runs

        val result =
            service.deleteCookie(
                DeleteCookieJarCookieInput(
                    name = "session",
                    domain = "example.com",
                ),
            )

        assertEquals(1, result.deleted)
        verify(exactly = 1) {
            cookieJar.setCookie(
                "session",
                "",
                "/",
                ".example.com",
                any(),
            )
        }
    }

    private fun mockCookie(
        name: String,
        value: String,
        domain: String?,
        path: String?,
        expiration: ZonedDateTime?,
    ): Cookie {
        val cookie = mockk<Cookie>()
        every { cookie.name() } returns name
        every { cookie.value() } returns value
        every { cookie.domain() } returns domain
        every { cookie.path() } returns path
        every { cookie.expiration() } returns Optional.ofNullable(expiration)
        return cookie
    }
}
