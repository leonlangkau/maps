package au.radar.app

import au.radar.core.HttpReply
import au.radar.core.HttpTransport
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * The concrete transport for [au.radar.core.RadarApi].
 *
 * Timeouts are short on purpose: on patchy rural coverage, giving up and
 * retrying beats hanging on a dead socket while the driver passes the thing we
 * were meant to warn about.
 */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpReply =
        send("GET", url, headers, null)

    override suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpReply {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .method(method, body?.toRequestBody(JSON) ?: emptyBodyFor(method))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        continuation.resume(HttpReply(it.code, text))
                    }
                }
            })
        }
    }

    /** POST and PUT need a body even when empty; GET and DELETE must not have one. */
    private fun emptyBodyFor(method: String) =
        if (method == "POST" || method == "PUT" || method == "PATCH") {
            ByteArray(0).toRequestBody(null)
        } else {
            null
        }
}
