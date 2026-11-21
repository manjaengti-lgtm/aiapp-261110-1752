// === ABOUT: "Continue with Google" — uses the Google account already on the phone to send through Gmail ===
package com.arin.civicreport.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import javax.mail.internet.MimeMessage
import kotlin.coroutines.resume

/**
 * Asks Android for permission to send mail as the Google account on the phone. The person picks
 * their account and taps Allow — no email or password is typed, and nothing is stored but the
 * address. Google keeps the access; the app asks for a fresh token each time it sends.
 */
class GoogleMailer(private val context: Context) {

    sealed interface Auth {
        data class Granted(val token: String) : Auth
        data class NeedsApproval(val intent: PendingIntent) : Auth
        data class Failed(val message: String) : Auth
    }

    private val scopes = listOf(
        Scope("https://www.googleapis.com/auth/gmail.send"),
        Scope("https://www.googleapis.com/auth/userinfo.email")
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    private fun client() = Identity.getAuthorizationClient(context)

    suspend fun authorize(): Auth = suspendCancellableCoroutine { cont ->
        client().authorize(AuthorizationRequest.builder().setRequestedScopes(scopes).build())
            .addOnSuccessListener { r ->
                if (!cont.isActive) return@addOnSuccessListener
                val pi = r.pendingIntent
                val token = r.accessToken
                cont.resume(
                    when {
                        r.hasResolution() && pi != null -> Auth.NeedsApproval(pi)
                        token != null -> Auth.Granted(token)
                        else -> Auth.Failed("Google did not give access.")
                    }
                )
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resume(Auth.Failed(explain(e)))
            }
    }

    /** Reads the approval the person just gave on Google's screen. */
    fun resultFrom(data: Intent?): Auth = runCatching {
        val r: AuthorizationResult = client().getAuthorizationResultFromIntent(data)
        r.accessToken?.let { Auth.Granted(it) } ?: Auth.Failed("Google did not give access.")
    }.getOrElse { Auth.Failed(explain(it)) }

    private fun explain(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "10:" in m || "DEVELOPER_ERROR" in m ->
                "This copy of the app is not registered with Google yet (developer setup needed)."
            "16:" in m || "CANCELED" in m.uppercase() -> "Sign-in was cancelled."
            "7:" in m || "NETWORK" in m.uppercase() -> "No internet connection."
            else -> m.ifBlank { "Google sign-in failed." }
        }
    }

    /** A fresh token without showing anything, or null if the person must approve again. */
    suspend fun token(): String? = (authorize() as? Auth.Granted)?.token

    suspend fun emailFor(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder().url("https://www.googleapis.com/oauth2/v3/userinfo")
                    .header("Authorization", "Bearer $token").build()
            ).execute().use { r ->
                if (!r.isSuccessful) return@use null
                Json.parseToJsonElement(r.body!!.string()).jsonObject["email"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    /** Sends one ready-made email through Gmail (up to 35 MB). Throws with a clear reason on failure. */
    suspend fun send(token: String, message: MimeMessage) = withContext(Dispatchers.IO) {
        fun call(t: String) = http.newCall(
            Request.Builder()
                .url("https://gmail.googleapis.com/upload/gmail/v1/users/me/messages/send?uploadType=media")
                .header("Authorization", "Bearer $t")
                .post(object : RequestBody() {
                    override fun contentType() = "message/rfc822".toMediaType()
                    override fun writeTo(sink: BufferedSink) = message.writeTo(sink.outputStream())
                })
                .build()
        ).execute()

        var response = call(token)
        if (response.code == 401) {
            // Token went stale; drop it and ask Google for a new one once.
            response.close()
            runCatching { GoogleAuthUtil.clearToken(context, token) }
            val fresh = token() ?: throw IllegalStateException("Google sign-in has expired. Tap \"Continue with Google\" again.")
            response = call(fresh)
        }
        response.use { r ->
            if (!r.isSuccessful) {
                val detail = r.body?.string().orEmpty()
                throw IllegalStateException(
                    when (r.code) {
                        403 -> if ("accessNotConfigured" in detail || "SERVICE_DISABLED" in detail)
                            "Gmail sending is not switched on for this app yet (developer setup needed)."
                        else "Gmail refused to send (daily limit reached or permission removed)."
                        413 -> "The email is too large for Gmail."
                        else -> "Gmail error ${r.code}."
                    }
                )
            }
        }
    }

    suspend fun signOut(token: String?) {
        if (token != null) withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, token) } }
    }
}
