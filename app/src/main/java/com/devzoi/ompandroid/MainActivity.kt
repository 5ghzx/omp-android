package com.devzoi.ompandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DEFAULT_RELAY = "wss://my.omp.sh"
private const val PROTO = 3
private data class Link(val wsUrl: String, val key: ByteArray, val writeToken: ByteArray?)
private data class Line(val role: String, val text: String)

private fun parseLink(input: String): Link {
    var text = input.trim().replace("%23", "#", ignoreCase = true)
    val bare = Regex("^([A-Za-z0-9_-]{10,64})[.#]([A-Za-z0-9_-]+)$").find(text)
    if (bare != null) {
        text = "$DEFAULT_RELAY/r/${bare.groupValues[1]}.${bare.groupValues[2]}"
    } else if (!text.contains("://")) {
        text = "wss://$text"
    }

    val uri = try { URI(text) } catch (e: Exception) { error("Invalid OMP collab link") }

    // OMP's QR/browser link is normally https://my.omp.sh/#<room>.<secret>.
    // The actual relay link is stored in the URL fragment, so parse it recursively.
    if ((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.rawFragment.isNullOrEmpty()) {
        return parseLink(uri.rawFragment!!)
    }

    val match = Regex("^/r/([A-Za-z0-9_-]{10,64})(?:\\.([A-Za-z0-9_-]+))?$").matchEntire(uri.path)
        ?: error("Invalid OMP collab link")
    val secretText = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
        ?: uri.rawFragment?.takeIf { it.isNotEmpty() }
        ?: error("Missing key")
    val secret = try { Base64.getUrlDecoder().decode(secretText) } catch (e: Exception) { error("Invalid OMP collab key") }
    require(secret.size == 32 || secret.size == 48) { "OMP key must be 32 or 48 bytes" }
    val scheme = when (uri.scheme.lowercase()) { "https" -> "wss"; "http" -> "ws"; else -> uri.scheme.lowercase() }
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return Link("$scheme://${uri.host}$port/r/${match.groupValues[1]}", secret.copyOfRange(0, 32), if (secret.size == 48) secret.copyOfRange(32, 48) else null)
}

private fun seal(key: ByteArray, json: String): ByteArray {
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
}

private fun open(key: ByteArray, data: ByteArray): String {
    require(data.size > 12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12)))
    return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
}

private fun envelope(peer: Int, sealed: ByteArray): ByteArray = ByteBuffer.allocate(4 + sealed.size).putInt(peer).put(sealed).array()
private fun contentText(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> buildString { for (i in 0 until value.length()) append(value.optJSONObject(i)?.optString("text").orEmpty()) }
    else -> ""
}

class MainActivity : ComponentActivity() {
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra("qr_value")?.let { scannedLink = it }
    }
    private var scannedLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { OmpApp() } }

    @Composable private fun OmpApp() {
        var linkText by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Disconnected") }
        var lines by remember { mutableStateOf(listOf<Line>()) }
        var socket by remember { mutableStateOf<WebSocket?>(null) }
        var parsed by remember { mutableStateOf<Link?>(null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(scannedLink) { scannedLink?.let { linkText = it; scannedLink = null } }
        fun send(frame: JSONObject) { val link = parsed ?: return; socket?.send(envelope(0, seal(link.key, frame.toString())).toByteString()) }
        fun addEntry(entry: JSONObject?) { if (entry == null || entry.optString("type") != "message") return; val message = entry.optJSONObject("message") ?: return; val text = contentText(message.opt("content")); if (text.isNotBlank()) lines = lines + Line(message.optString("role", "unknown"), text) }
        fun renderEvent(event: JSONObject?) { if (event == null) return; val message = event.optJSONObject("message"); val text = contentText(message?.opt("content")); if (text.isNotBlank()) lines = lines + Line("assistant", text); if (event.optString("type") == "tool_execution_start") lines = lines + Line("tool", "▶ ${event.optString("toolName")}") }

        fun connect() {
            try {
                val link = parseLink(linkText)
                parsed = link
                lines = emptyList()
                status = "Connecting…"
                socket?.close(1000, "reconnect")
                socket = OkHttpClient().newWebSocket(Request.Builder().url(link.wsUrl).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        status = "Connected"
                        val hello = JSONObject().put("t", "hello").put("proto", PROTO).put("name", "OMP Android")
                        link.writeToken?.let { hello.put("writeToken", Base64.getUrlEncoder().withoutPadding().encodeToString(it)) }
                        webSocket.send(envelope(0, seal(link.key, hello.toString())).toByteString())
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { scope.launch(Dispatchers.Main) { try { val raw = bytes.toByteArray(); require(raw.size >= 4); val json = JSONObject(open(link.key, raw.copyOfRange(4, raw.size))); when (json.optString("t")) { "welcome" -> status = if (json.optBoolean("readOnly")) "Connected · read-only" else "Connected · full control"; "snapshot-chunk" -> json.optJSONArray("entries")?.let { for (i in 0 until it.length()) addEntry(it.optJSONObject(i)) }; "entry" -> addEntry(json.optJSONObject("entry")); "event" -> renderEvent(json.optJSONObject("event")); "error" -> lines = lines + Line("error", json.optString("message")); "bye" -> status = "Disconnected: ${json.optString("reason")}" } } catch (e: Exception) { lines = lines + Line("error", "Protocol error: ${e.message}") } } }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch(Dispatchers.Main) { status = "Connection failed: ${t.message ?: "unknown error"}" } }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch(Dispatchers.Main) { status = "Disconnected" } }
                })
            } catch (e: Exception) { status = "Invalid link: ${e.message}" }
        }

        MaterialTheme { Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("OMP Android", style = MaterialTheme.typography.headlineSmall); Text(status, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { OutlinedTextField(value = linkText, onValueChange = { linkText = it }, modifier = Modifier.weight(1f), label = { Text("OMP collab link") }, singleLine = true); Button(onClick = { scanner.launch(Intent(this@MainActivity, ScannerActivity::class.java)) }) { Text("Scan QR") } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = ::connect) { Text("Join") }; OutlinedButton(onClick = { socket?.close(1000, "leave"); socket = null; status = "Disconnected" }) { Text("Leave") }; OutlinedButton(onClick = { send(JSONObject().put("t", "abort")) }, enabled = parsed?.writeToken != null) { Text("Stop") } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) { items(lines) { line -> Text("${line.role}: ${line.text}", Modifier.padding(4.dp)) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f), placeholder = { Text("Prompt…") }); Button(onClick = { if (prompt.isNotBlank()) { send(JSONObject().put("t", "prompt").put("text", prompt)); prompt = "" } }, enabled = parsed?.writeToken != null) { Text("Send") } }
        } }
    }
}
