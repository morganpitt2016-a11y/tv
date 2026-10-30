package com.morgan.minimallauncher

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/**
 * A conversation with Claude through the official Anthropic Java SDK,
 * using the person's own API key (stored on the TV only).
 *
 * Server-side fallbacks are on: if Claude Opus 5.5's safety filters
 * decline a harmless request, the API retries it on another Claude model
 * instead of refusing.
 */
class ClaudeChat(context: Context) {

    companion object {
        private const val PREFS = "claude"
        private const val KEY = "api_key"
        const val MODEL = "claude-opus-5-5"

        private const val SYSTEM = "You are Claude, built into a TV home-screen app and talking to someone " +
            "on their sofa through a TV remote. Answer in plain text (no markdown, tables, bullet symbols or " +
            "headings), since it is shown as-is in large type on a TV. Keep replies short and easy to read " +
            "from across a room: usually two to five sentences, longer only when asked. You can't control " +
            "the TV or open apps; if asked, explain where to find it on the home screen instead."

        fun getApiKey(c: Context): String? =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }

        fun setApiKey(c: Context, key: String?) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (key.isNullOrBlank()) remove(KEY) else putString(KEY, key.trim())
            }.apply()
        }
    }

    sealed class Reply {
        data class Text(val text: String, val truncated: Boolean) : Reply()
        data class Failed(val message: String) : Reply()
    }

    private val appContext = context.applicationContext
    private val history = mutableListOf<BetaMessageParam>()
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    fun clear() = synchronized(history) { history.clear() }

    /** Blocking: call off the main thread. */
    fun send(userText: String): Reply {
        val key = getApiKey(appContext) ?: return Reply.Failed("Add your Anthropic API key first.")
        val api = clientFor(key)

        val turn = BetaMessageParam.ofUser(userText)
        val params = synchronized(history) {
            history += turn
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(SYSTEM)
                .messages(history.toList())
                .addBeta(AnthropicBeta.of("server-side-fallback-2026-07-01"))
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
        }

        return try {
            val response = api.beta().messages().create(params)
            if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                // Drop this turn so the conversation stays valid for the next question.
                synchronized(history) { history.remove(turn) }
                return Reply.Failed("Claude can’t help with that one. Try asking another way.")
            }
            val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("\n\n").trim()
            // Keep the whole reply (thinking included) so follow-up questions have full context.
            synchronized(history) { history += response.toParam() }
            Reply.Text(text.ifEmpty { "(No answer.)" }, response.stopReason().orElse(null) == BetaStopReason.MAX_TOKENS)
        } catch (e: UnauthorizedException) {
            fail(turn, "That API key wasn’t accepted. Check it in Settings › Claude.")
        } catch (e: PermissionDeniedException) {
            fail(turn, "This API key doesn’t have access to Claude Opus 5.5.")
        } catch (e: RateLimitException) {
            fail(turn, "Too many requests right now. Wait a moment and try again.")
        } catch (e: BadRequestException) {
            val detail = e.message.orEmpty()
            fail(turn, if (detail.contains("credit", ignoreCase = true))
                "Your Anthropic account is out of credit. Top it up at console.anthropic.com."
            else "Claude couldn’t take that request (${detail.take(140)}).")
        } catch (e: InternalServerException) {
            fail(turn, "Claude is having trouble right now (${e.statusCode()}). Try again shortly.")
        } catch (e: AnthropicServiceException) {
            fail(turn, "Claude returned an error (${e.statusCode()}). Try again.")
        } catch (e: AnthropicIoException) {
            fail(turn, "Couldn’t reach Claude. Check the TV’s internet connection.")
        } catch (e: Exception) {
            fail(turn, "Something went wrong: ${e.javaClass.simpleName}")
        }
    }

    private fun fail(turn: BetaMessageParam, message: String): Reply {
        synchronized(history) { history.remove(turn) }
        return Reply.Failed(message)
    }

    private fun clientFor(key: String): AnthropicClient {
        val existing = client
        if (existing != null && clientKey == key) return existing
        existing?.close()
        return AnthropicOkHttpClient.builder().apiKey(key).build().also {
            client = it
            clientKey = key
        }
    }

    fun close() {
        client?.close()
        client = null
    }
}
