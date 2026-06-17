package ai.mytextpal.miniclaw

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Callbacks fire on OkHttp background threads — the caller must marshal to the UI thread. */
interface MiniClawListener {
    fun onStatus(status: String)
    fun onDelta(text: String)
    fun onReplyComplete(content: String)
}

/**
 * Thin client to the miniclaw server. The phone behaves like a "web" channel — no server
 * changes required:
 *   - POST /api/transcribe   audio -> text (gpt-4o-transcribe)
 *   - WebSocket /ws?token=   send {type:"message",content}; receive delta / stream_end
 */
class MiniClawClient(
    private val baseUrl: String,
    private val wsUrl: String,
    private val token: String,
    private val listener: MiniClawListener,
) {
    // WebSocket needs no read timeout; a ping keeps it alive across idle/sleep.
    private val wsHttp = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Short-lived REST calls get sane timeouts.
    private val apiHttp = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null

    fun connect() {
        if (ws != null) return
        // channel=phone marks this as the Light Phone voice channel: the server routes only this
        // client's own replies back to it (so web-UI chats don't get spoken here) and tells the
        // agent it's a voice surface so it answers concisely.
        val req = Request.Builder().url("$wsUrl?token=$token&channel=phone").build()
        ws = wsHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onStatus("connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWsMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                listener.onStatus("disconnected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                listener.onStatus("ws error: ${t.message}")
            }
        })
    }

    private fun handleWsMessage(text: String) {
        val obj = try { JSONObject(text) } catch (e: Exception) { return }
        when (obj.optString("type")) {
            "typing" -> if (obj.optBoolean("active")) listener.onStatus("thinking")
            "delta" -> listener.onDelta(obj.optString("text"))
            "stream_end" -> listener.onReplyComplete(obj.optString("content"))
            "error" -> listener.onStatus("error: ${obj.optString("message")}")
        }
    }

    /**
     * Send a user message to the agent. Voice turns request low reasoning effort for speed;
     * the server bumps to high when the user asks it to ("think hard about…"). Returns false
     * if the socket isn't open.
     */
    fun sendText(content: String, effort: String = "low"): Boolean {
        val w = ws ?: return false
        val msg = JSONObject()
            .put("type", "message")
            .put("content", content)
            .put("effort", effort)
        return w.send(msg.toString())
    }

    /** Tell the server to abort the in-progress generation (maps to abortCurrent()). */
    fun abort() {
        ws?.send(JSONObject().put("type", "stop").toString())
    }

    /** Upload audio, return the transcript or null. Blocking — call off the main thread. */
    fun transcribe(file: File, mimeType: String = "audio/mp4"): String? {
        return try {
            val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            val body = JSONObject()
                .put("audioBase64", b64)
                .put("mimeType", mimeType)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/api/transcribe")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            apiHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Don't let an auth/server failure masquerade as "couldn't hear that".
                    Log.w(TAG, "transcribe HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return null
                }
                val json = JSONObject(resp.body?.string() ?: return null)
                if (json.optBoolean("ok")) json.optString("text").ifBlank { null } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "transcribe failed", e)
            null
        }
    }

    /** Synthesize speech for one chunk of text via miniclaw's /api/tts. Blocking — call off main. */
    fun synthesize(text: String, voice: String = "alloy"): ByteArray? {
        return try {
            val body = JSONObject()
                .put("text", text)
                .put("voice", voice)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$baseUrl/api/tts")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()
            apiHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "tts HTTP ${resp.code}")
                    return null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "tts failed", e)
            null
        }
    }

    fun close() {
        ws?.close(1000, "bye")
        ws = null
    }

    companion object {
        private const val TAG = "MiniClaw"
    }
}
