package dev.helfferich.judgelies.judging

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import dev.helfferich.judgelies.openrouter.JudgeRaw
import dev.helfferich.judgelies.openrouter.OpenRouterException
import dev.helfferich.judgelies.openrouter.buildChatRequest
import dev.helfferich.judgelies.openrouter.parseCompletion

/**
 * One chat completion against OpenRouter.
 * A 401 or 403 aborts. 429 and 5xx are retried once. The API key is stripped from any error text that is stored.
 */
class OpenRouterJudge(
    private val apiKey: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
    private val endpoint: URI = URI.create("https://openrouter.ai/api/v1/chat/completions"),
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val hardDeadlineSeconds: Long = 120,
) : JudgeClient {
    override fun complete(request: JudgeRequest): JudgeRaw {
        val payload = buildChatRequest(
            request.model,
            request.temperature,
            request.maxTokens,
            request.system,
            request.user,
        )
        var lastStatus = 0
        var lastBody = ""
        for (attempt in 0 until 2) {
            val httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/LeoHelfferich/llm-judges-and-trust")
                .header("X-Title", "Who judges the judge?")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            // The per-request timeout only covers the response headers. OpenRouter can send headers and then
            // keep the body open with keep-alive padding, so a stalled upstream hangs forever without a hard deadline.
            val future = http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
            val response = try {
                future.get(hardDeadlineSeconds, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                lastStatus = 504
                lastBody = "No complete response within $hardDeadlineSeconds s (client-side deadline)."
                if (attempt == 1) break
                sleep(5_000)
                continue
            } catch (ex: ExecutionException) {
                lastStatus = 599
                lastBody = "Transport error: ${ex.cause?.javaClass?.simpleName ?: "unknown"}"
                if (attempt == 1) break
                sleep(5_000)
                continue
            }
            lastStatus = response.statusCode()
            lastBody = response.body()
            if (lastStatus == 401 || lastStatus == 403) {
                throw OpenRouterException("OpenRouter rejected the API key (HTTP $lastStatus).")
            }
            if (lastStatus == 200) return parseCompletion(lastBody)
            val retryable = lastStatus == 429 || lastStatus in 500..599
            if (!retryable || attempt == 1) break
            val retryAfter = response.headers().firstValue("retry-after").orElse("2").toLongOrNull() ?: 2L
            sleep(retryAfter.coerceIn(1, 20) * 1000)
        }
        val detail = lastBody.take(300).replace(apiKey, "[redacted]")
        throw OpenRouterException("OpenRouter HTTP $lastStatus: $detail")
    }
}
