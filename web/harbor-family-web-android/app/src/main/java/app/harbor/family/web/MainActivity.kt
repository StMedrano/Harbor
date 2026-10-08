package app.harbor.family.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Harbor Family Web: a thin native shell around the live HTTPS site.
 * The app contains no copy of the site, so every Vercel deployment shows up the next time the
 * page loads (open the app, or pull down to refresh). Nothing needs to be rebuilt for site updates.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var offline: View
    private val siteHost: String = Uri.parse(BuildConfig.SITE_URL).host.orEmpty()
    private var failed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = getColor(R.color.harbor_bg)

        web = WebView(this).apply {
            setBackgroundColor(bg)
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true            // the site is a JS app
                domStorageEnabled = true            // localStorage / IndexedDB (sign-in session, device key)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT // honours the site's cache headers, so new deploys are picked up
                setSupportZoom(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.scheme == "https" && u.host == siteHost) return false
                    // Anything else (other sites, mailto:, tel:) opens outside the app.
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { /* no handler */ }
                    return true
                }
                override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) { failed = false }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) { failed = true; offline.visibility = View.VISIBLE }
                }
                override fun onPageFinished(v: WebView, url: String) {
                    refresh.isRefreshing = false
                    if (!failed) offline.visibility = View.GONE
                }
            }
        }

        refresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(getColor(R.color.harbor_brand))
            setOnRefreshListener { load() }
            // Only allow pull-to-refresh when the page is scrolled to the top.
            setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
            addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        offline = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(64, 64, 64, 64)
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "Can’t reach Harbor Family"
                textSize = 22f; setTextColor(getColor(R.color.harbor_brand)); gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Check your internet connection and try again."
                textSize = 15f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; setPadding(0, 16, 0, 32)
            })
            addView(Button(context).apply { text = "Try again"; setOnClickListener { load() } })
        }

        setContentView(FrameLayout(this).apply {
            addView(refresh, FrameLayout.LayoutParams(-1, -1))
            addView(offline, FrameLayout.LayoutParams(-1, -1))
        })

        // Back: let the site close sheets / go back a screen, then browser history, then leave the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.harborBack&&window.harborBack()") { handled ->
                    when {
                        handled == "true" -> Unit
                        web.canGoBack() -> web.goBack()
                        else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                    }
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else load()
    }

    private fun load() { failed = false; offline.visibility = View.GONE; web.loadUrl(BuildConfig.SITE_URL) }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onResume() {
        super.onResume(); web.onResume()
        // Coming back after a while: reload so the newest deployment is shown.
        if (web.url == null || failed) load()
    }
}
