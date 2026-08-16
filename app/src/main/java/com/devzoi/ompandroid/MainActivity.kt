package com.devzoi.ompandroid

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    private var scannedLink by mutableStateOf<String?>(null)
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("qr_value")?.let { scannedLink = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OmpApp() }
    }

    @Composable
    private fun OmpApp() {
        var linkText by remember { mutableStateOf("") }
        var joined by remember { mutableStateOf(false) }
        var webView by remember { mutableStateOf<WebView?>(null) }

        LaunchedEffect(scannedLink) {
            scannedLink?.let {
                linkText = it
                scannedLink = null
            }
        }

        if (joined) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { webView?.goBack() }) { Text("Back") }
                    OutlinedButton(onClick = { webView?.reload() }) { Text("Reload") }
                    OutlinedButton(onClick = { joined = false }) { Text("Session") }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mediaPlaybackRequiresUserGesture = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    return false
                                }
                            }
                            webView = this
                            loadUrl(linkText.trim())
                        }
                    },
                    update = { view ->
                        webView = view
                    }
                )
            }
        } else {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("OMP Android", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Uses OMP's official collab-web client inside a native Android WebView.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OMP collab link") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scanner.launch(Intent(this@MainActivity, ScannerActivity::class.java)) }
                        ) { Text("Scan QR") }
                        Button(
                            onClick = {
                                if (linkText.trim().isNotEmpty()) joined = true
                            },
                            enabled = linkText.trim().isNotEmpty()
                        ) { Text("Join") }
                    }
                }
            }
        }
    }
}
