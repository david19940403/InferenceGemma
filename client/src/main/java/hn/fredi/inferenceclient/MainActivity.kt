package hn.fredi.inferenceclient

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.*
import java.net.URI

/** Thin Android client: all inference and credentials for SSH stay on the host device. */
class MainActivity : Activity() {
    private var browser: WebView? = null
    private var address = ""
    private var connected = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,16,23))
        setOnApplyWindowInsetsListener { v, i -> v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom); i }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = savedInstanceState?.getString("address") ?: getPreferences(MODE_PRIVATE).getString("address", "").orEmpty()
        if(savedInstanceState?.getBoolean("connected") == true) showBrowser() else showConnection()
    }
    private fun showConnection() {
        connected = false; browser?.destroy(); browser = null
        val outer = root(); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(36), dp(24), dp(24)) }
        fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0,0,0,dp(20)) }
        content.addView(text("Inference Client", 28f))
        content.addView(text("Conecta con el dispositivo que ejecuta el modelo.", 16f, Color.LTGRAY))
        val input = EditText(this).apply { hint = "http://192.168.1.100:11435"; setText(address); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true; contentDescription = "Dirección del servidor" }
        content.addView(input, LinearLayout.LayoutParams(-1,dp(64)))
        val error = text("",14f,Color.rgb(255,160,160)); content.addView(error)
        content.addView(Button(this).apply { text = "Conectar"; setOnClickListener {
            try {
                val value = input.text.toString().trim().trimEnd('/')
                val uri = URI(value)
                require(uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "Introduce solo la dirección HTTP(S) del servidor y su puerto" }
                address = value; getPreferences(MODE_PRIVATE).edit().putString("address",address).apply(); showBrowser()
            } catch(e: Exception) { error.text = e.message ?: "Dirección inválida" }
        } }, LinearLayout.LayoutParams(-1,dp(56)))
        content.addView(text("La clave de acceso aparece en Acceso remoto, dentro de la app del servidor. Este cliente no descarga ni ejecuta modelos.",14f,Color.LTGRAY).apply { setPadding(0,dp(24),0,0) })
        outer.addView(content); setContentView(outer)
    }
    @Suppress("SetJavaScriptEnabled")
    private fun showBrowser() {
        connected = true
        val outer = root()
        outer.addView(Button(this).apply { text = "Cambiar servidor"; setOnClickListener { showConnection() } }, LinearLayout.LayoutParams(-1,dp(48)))
        val origin = URI(address)
        fun allowed(url: String): Boolean = runCatching { val u=URI(url); u.scheme == origin.scheme && u.host == origin.host && u.port == origin.port && u.userInfo == null }.getOrDefault(false)
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            userAgentString += " InferenceLocalClient/1"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view,false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean = !allowed(request.url.toString())
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if(request.isForMainFrame) Toast.makeText(this@MainActivity,"No se pudo conectar. Revisa la dirección y que el servidor esté activo.",Toast.LENGTH_LONG).show()
            }
        }
        view.setDownloadListener { url, _, _, _, _ -> if(allowed(url)) startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
        browser = view; outer.addView(view,LinearLayout.LayoutParams(-1,0,1f)); setContentView(outer); view.loadUrl(address + "/")
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("address",address);outState.putBoolean("connected",connected);super.onSaveInstanceState(outState) }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if(connected) showConnection() else super.onBackPressed() }
    override fun onDestroy() { browser?.destroy(); super.onDestroy() }
}
