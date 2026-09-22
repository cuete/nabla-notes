package com.nabla.notes.summarizer

import com.nabla.notes.repository.DictationRepository
import com.nabla.voice.TranscriptEntry
import com.nabla.voice.formatTranscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val MODEL = "openclaw/main"
private const val SESSION_USER = "alejandro"

/**
 * The only [Summarizer] implementation today — chato's OpenClaw gateway (same chat-completions
 * shape as its ChatoGatewayRepository.chat(), reused here rather than reinvented). Gateway
 * URL/token live in [DictationRepository], set via the Dictation settings dialog.
 */
@Singleton
class OpenClawSummarizer @Inject constructor(
    private val dictationRepository: DictationRepository,
    private val httpClient: OkHttpClient,
) : Summarizer {

    override suspend fun summarize(transcript: List<TranscriptEntry>, notes: String): Result<String> =
        chat(buildPrompt(transcript, notes))

    override suspend fun organize(content: String): Result<String> =
        chat(buildOrganizePrompt(content))

    private suspend fun chat(prompt: String): Result<String> {
        val url = dictationRepository.gatewayUrl()
        val token = dictationRepository.gatewayToken()
        if (url.isBlank() || token.isBlank()) {
            return Result.failure(IllegalStateException("Gateway URL/token not configured. Open Settings."))
        }

        val bodyJson = JSONObject().apply {
            put("model", MODEL)
            put("user", SESSION_USER)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            )
        }.toString()

        val requestBody = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$url/v1/chat/completions")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: throw IllegalStateException("Empty response body.")

                if (!response.isSuccessful) {
                    throw RuntimeException("Gateway error ${response.code}: $responseBody")
                }

                JSONObject(responseBody)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        }
    }

    /**
     * The instruction itself is written in Spanish (matches how Alejandro talks to his own
     * tools), but that's just the language the instruction happens to be phrased in — it's not
     * what the model should reply in. Without the explicit line below, the model was defaulting
     * to Spanish for every summary regardless of what language the transcript/notes were
     * actually in (2026-09-16 feedback). Separating "what language am I instructed in" from
     * "what language should the content be" is the actual fix — not detecting the language in
     * app code, which would just be reimplementing what the model already does natively.
     */
    internal fun buildPrompt(transcript: List<TranscriptEntry>, notes: String): String = buildString {
        appendLine("Sos un asistente que resume una sesión de dictado (transcripción de voz y notas escritas) en un resumen claro y organizado.")
        appendLine("Incluí: resumen general, puntos clave, y próximos pasos si los hay.")
        appendLine("IMPORTANTE: Respondé en el mismo idioma del contenido de abajo (transcripción y notas) — si está en inglés, respondé en inglés; si está en español, respondé en español; si está mezclado, usá el idioma predominante. No traduzcas el contenido a otro idioma.")
        appendLine()
        if (notes.isNotBlank()) {
            appendLine("Notas escritas durante la sesión:")
            appendLine("---")
            appendLine(notes)
            appendLine("---")
            appendLine()
        }
        appendLine("Transcripción:")
        appendLine("---")
        appendLine(formatTranscript(transcript))
        append("---")
    }

    /**
     * Reorganize-only: same language as the note, no invented content. The reply must be the
     * note itself (no preamble/fences) since the editor drops it in verbatim on Apply.
     */
    internal fun buildOrganizePrompt(content: String): String = buildString {
        appendLine("Sos un asistente que limpia y reorganiza una nota en markdown.")
        appendLine("Corregí ortografía y puntuación, agrupá ideas relacionadas, usá encabezados y listas donde ayuden, y eliminá repeticiones. Conservá todos los datos, nombres, números, fechas, links e imágenes (![...](...)) tal cual.")
        appendLine("NO agregues información nueva ni comentarios tuyos. Respondé en el mismo idioma de la nota, sin traducir.")
        appendLine("Devolvé ÚNICAMENTE la nota reorganizada en markdown, sin explicación ni bloque de código envolvente.")
        appendLine()
        appendLine("Nota:")
        appendLine("---")
        appendLine(content)
        append("---")
    }
}
