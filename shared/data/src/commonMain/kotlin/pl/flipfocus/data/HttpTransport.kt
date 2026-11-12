package pl.flipfocus.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*

data class HttpResult(val status: Int, val body: String)

/** A narrow transport keeps server integration deterministic in common tests. */
interface HttpTransport {
    suspend fun request(method: String, url: String, token: String? = null, body: String? = null): HttpResult
    fun close() {}
}

expect fun createPlatformHttpClient(): HttpClient

internal fun HttpClientConfig<*>.configureSocialClient() {
    expectSuccess = false
    // Never forward an account token to an unexpected redirect destination.
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
}

class KtorHttpTransport(private val client: HttpClient = createPlatformHttpClient()) : HttpTransport {
    override suspend fun request(method: String, url: String, token: String?, body: String?): HttpResult {
        val response = client.request(url) {
            this.method = HttpMethod.parse(method)
            accept(ContentType.Application.Json)
            if (token != null) bearerAuth(token)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = response.bodyAsText()
        require(text.length <= 2_000_000) { "Response is too large" }
        return HttpResult(response.status.value, text)
    }

    override fun close() { client.close() }
}

internal fun normalizedEndpoint(value: String): String {
    val text = value.trim().trimEnd('/')
    val url = Url(text)
    require(url.host.isNotBlank() && url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty()) {
        "Use a server address without credentials, query or fragment"
    }
    require(url.protocol == URLProtocol.HTTPS ||
        url.protocol == URLProtocol.HTTP && url.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")) {
        "A secure HTTPS server address is required"
    }
    return text
}
