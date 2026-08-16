package com.devzoi.ompandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
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

private data class Link(val wsUrl: String, val roomId: String, val key: ByteArray, val writeToken: ByteArray?)
private data class Line(val role: String, val text: String)

private fun b64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
private fun parseLink(input: String): Link {
    var text = input.trim().replace("%23", "#", true)
    val bare = Regex("^([A-Za-z0-9_-]{10,64})[.#]([A-Za-z0-9_-]+)$").find(text)
    if (bare != null) text = "$DEFAULT_RELAY/r/${bare.groupValues[1]}.${bare.groupValues[2]}"
    else if (!text.contains("://")) text = "wss://$text"
    val u = URI(text)
    if ((u.scheme == "http" || u.scheme == "https") && u.rawFragment != null) return parseLink(u.rawFragment)
    val m = Regex("^/r/([A-Za-z0-9_-]{10,64})(?:\\.([A-Za-z0-9_-]+))?$").matchEntire(u.path)
        ?: error("Invalid OMP collab link")
    val secretText = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: u.rawFragment ?: error("Missing key")
    val secret = b64(secretText)
    require(secret.size == 32 || secret.size == 48) { "Key must be 32 or 48 bytes" }
    val scheme = when (u.scheme) { "https" -> "wss"; "http" -> "ws"; else -> u.scheme }
    val port = if (u.port > 0) ":${u.port}" else ""
    return Link("$scheme://${u.host}$port/r/${m.groupValues[1]}", m.groupValues[1], secret.copyOfRange(0,32), if (secret.size == 48) secret.copyOfRange(32,48) else null)
}

private fun seal(key: ByteArray, json: String): ByteArray {
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    val ct = c.doFinal(json.toByteArray(Charsets.UTF_8))
    return iv + ct
}

private fun open(key: ByteArray, data: ByteArray): String {
    require(data.size > 12)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data.copyOfRange(0,12)))
    return c.doFinal(data.copyOfRange(12,data.size)).toString(Charsets.UTF_8)
}

private fun envelope(peer: Int, sealed: ByteArray): ByteArray = ByteBuffer.allocate(4 + sealed.size).putInt(peer).put(sealed).array()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { OmpApp() } }
}

@Composable
private fun OmpApp() {
    var linkText by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Disconnected") }
    var lines by remember { mutableStateOf(listOf<Line>()) }
    var ws by remember { mutableStateOf<WebSocket?>(null) }
    var parsed by remember { mutableStateOf<Link?>(null) }
    val scope = rememberCoroutineScope()

    fun send(frame: JSONObject) {
        val l = parsed ?: return
        ws?.send(envelope(0, seal(l.key, frame.toString())).toByteString())
    }
    fun connect() {
        try {
            val l = parseLink(linkText); parsed = l
            val client = OkHttpClient.Builder().build()
            status = "Connecting…"
            ws = client.newWebSocket(Request.Builder().url(l.wsUrl).build(), object: WebSocketListener() {
                override fun onOpen(s: WebSocket, r: Response) {
                    status = "Connected"
                    val hello = JSONObject().put("t","hello").put("proto",PROTO).put("name","Android")
                    l.writeToken?.let { hello.put("writeToken", Base64.getUrlEncoder().withoutPadding().encodeToString(it)) }
                    s.send(envelope(0, seal(l.key, hello.toString())).toByteString())
                }
                override fun onMessage(s: WebSocket, bytes: okio.ByteString) {
                    scope.launch(Dispatchers.Main) {
                        try {
                            val raw = bytes.toByteArray(); val payload = raw.copyOfRange(4, raw.size)
                            val j = JSONObject(open(l.key,payload)); val t=j.optString("t")
                            when(t) {
                                "welcome" -> status = if(j.optBoolean("readOnly")) "Connected · read-only" else "Connected · full control"
                                "snapshot-chunk" -> j.optJSONArray("entries")?.let { addEntries(it) }
                                "entry" -> addEntry(j.optJSONObject("entry"))
                                "event" -> renderEvent(j.optJSONObject("event"))
                                "error" -> lines = lines + Line("error",j.optString("message"))
                                "bye" -> status = "Disconnected: ${j.optString("reason")}"
                            }
                        } catch(e: Exception) { lines = lines + Line("error","Protocol error: ${e.message}") }
                    }
                }
                override fun onFailure(s: WebSocket, t: Throwable, r: Response?) { status = "Connection failed: ${t.message}" }
                override fun onClosed(s: WebSocket, code: Int, reason: String) { status = "Disconnected" }
            })
        } catch(e: Exception) { status = "Invalid link: ${e.message}" }
    }
    fun addEntry(e: JSONObject?) {
        if(e == null || e.optString("type") != "message") return
        val m=e.optJSONObject("message") ?: return; val role=m.optString("role"); val c=m.opt("content")
        val text=when(c) { is String -> c; is org.json.JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("text") }.joinToString(""); else -> "" }
        if(text.isNotBlank()) lines = lines + Line(role,text)
    }
    fun addEntries(a: org.json.JSONArray) { for(i in 0 until a.length()) addEntry(a.optJSONObject(i)) }
    fun renderEvent(e: JSONObject?) { if(e==null)return; when(e.optString("type")){"message_update" -> { val m=e.optJSONObject("message"); val c=m?.opt("content"); val text=if(c is String)c else if(c is org.json.JSONArray)(0 until c.length()).mapNotNull{c.optJSONObject(it)?.optString("text")}.joinToString("") else ""; if(text.isNotBlank()) lines = lines + Line("assistant",text) } "tool_execution_start" -> lines=lines+Line("tool", "▶ ${e.optString("toolName")}") } }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("OMP Android", style=MaterialTheme.typography.headlineSmall)
            Text(status, style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(linkText,{linkText=it},Modifier.fillMaxWidth(),label={Text("OMP collab link")},singleLine=true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){ Button(onClick={connect()}){Text("Join")}; OutlinedButton(onClick={ws?.close(1000,"leave")}){Text("Leave")}; OutlinedButton(onClick={send(JSONObject().put("t","abort"))},enabled=parsed?.writeToken!=null){Text("Stop")} }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical=8.dp)){items(lines){l->Text("${l.role}: ${l.text}",Modifier.padding(4.dp))}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){ OutlinedTextField(prompt,{prompt=it},Modifier.weight(1f),placeholder={Text("Prompt…")}); Button(onClick={if(prompt.isNotBlank()){send(JSONObject().put("t","prompt").put("text",prompt)); prompt=""}},enabled=parsed?.writeToken!=null){Text("Send")} }
        }
    }
}
