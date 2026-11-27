package `in`.melo.presentation.screens

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebStorage
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import `in`.melo.MeloModel
import `in`.melo.R
import `in`.melo.data.discord.DiscordAccount
import `in`.melo.data.discord.DiscordAccountStore
import `in`.melo.presentation.icons.MeloIcons

/**
 * Reads the token out of a completed login through a fresh iframe: Discord takes
 * `localStorage` away from the top window once its client boots, but a new
 * same-origin frame still sees the store. `alert` carries the value out with no
 * JavaScript bridge, and an empty alert on a miss lets the caller retry.
 */
private const val TOKEN_SNIPPET = """
    (function () {
      try {
        var frame = document.createElement('iframe');
        document.body.appendChild(frame);
        var token = frame.contentWindow.localStorage.token;
        document.body.removeChild(frame);
        alert(token ? token.slice(1, -1) : '');
      } catch (e) {
        alert('');
      }
    })()
"""

/** The token is written a moment after the client starts booting; 12 tries at 500 ms cover a slow boot. */
private const val MAX_TOKEN_ATTEMPTS = 12
private const val TOKEN_RETRY_MS = 500L
private const val LOGIN_URL = "https://discord.com/login"

/**
 * Removes the Discord session the sign-in WebView keeps (its token in the page's storage, and its
 * cookies). Left in place, signing out of Melo would sign straight back in on the next visit.
 */
internal fun forgetDiscordWebSession(then: () -> Unit = {}) {
    runCatching { WebStorage.getInstance().deleteOrigin("https://discord.com") }
    runCatching { CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); then() } }.onFailure { then() }
}

private val WEBVIEW_VERSION_TOKEN = Regex("""Version/\d+(\.\d+)*\s*""")

/** Only used when the platform user agent cannot be read, so the page is never blank. */
private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

/**
 * Removes the `; wv` and `Version/4.0` marks of a WebView. Discord reads that pair
 * as the long-unsupported "Android Browser 4.0" and serves a page that never
 * renders its login form. Editing the real string keeps the device and Chrome version.
 */
internal fun browserUserAgent(platformUserAgent: String?): String {
    val stripped = platformUserAgent
        ?.replace("; wv", "")
        ?.replace(WEBVIEW_VERSION_TOKEN, "")
        ?.replace("  ", " ")
        ?.trim()
    return stripped?.takeIf { it.contains("Chrome/") } ?: FALLBACK_USER_AGENT
}

/** A finished login lands on /app or /channels/@me, depending on how the session began. */
private fun isSignedIn(url: String?): Boolean =
    url != null && url.startsWith("https://discord.com/") && !url.contains("/login") && !url.contains("/register")

/**
 * Discord's own login page, so two-factor and passkeys work as on the web. Once
 * the page leaves the sign-in flow the account token is read once and stored
 * encrypted. No OAuth scope lets an app set a user's presence, so the token is
 * the only credential that can.
 *
 * Unlike the Google sign-in, this keeps the WebView's cookies: clearing them
 * could discard other WebView sessions.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordLoginScreen(model: MeloModel, navController: NavHostController) {
    val store = DiscordAccountStore.get(LocalContext.current)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler {
        val view = webView
        if (canGoBack && view != null) view.goBack() else navController.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text(stringResource(R.string.discord_sign_in_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(MeloIcons.Close, stringResource(R.string.action_cancel))
                    }
                },
            )
        },
    ) { inner ->
        val hasSong by model.ui.derivedHasSong.collectAsStateWithLifecycle()
        val pageColor = MaterialTheme.colorScheme.background.toArgb()
        Box(
            Modifier.padding(inner)
                .padding(bottom = if (hasSong) `in`.melo.presentation.components.MiniPlayerHeight else 0.dp)
                .fillMaxSize(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // A WRAP_CONTENT WebView gets a zero-height layout viewport, and Discord's
                        // client, sized by height: 100%, then paints an empty page.
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.userAgentString = browserUserAgent(settings.userAgentString)

                        var captured = false
                        var attempts = 0

                        fun harvestToken() {
                            if (captured) return
                            if (attempts >= MAX_TOKEN_ATTEMPTS) {
                                Log.w("MeloDiscord", "no token in localStorage after $attempts tries")
                                return
                            }
                            attempts++
                            evaluateJavascript(TOKEN_SNIPPET, null)
                        }

                        // Shown once the first page has painted: until then a WebView is a blank
                        // white sheet, which flashes against the dark page behind it.
                        visibility = android.view.View.INVISIBLE
                        // Opaque, in the page's own colour: an unstyled first frame is not white, and
                        // nothing behind the WebView has to be drawn through it.
                        setBackgroundColor(pageColor)
                        webViewClient = object : WebViewClient() {
                            override fun onPageCommitVisible(view: WebView, url: String?) {
                                if (view.visibility == android.view.View.INVISIBLE) view.visibility = android.view.View.VISIBLE
                            }

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                if (isSignedIn(url)) harvestToken()
                                return false
                            }

                            // The only hook that also fires for pushState, which is how the
                            // client moves from the login form into the app.
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                canGoBack = view?.canGoBack() == true
                                if (isSignedIn(url)) harvestToken()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                canGoBack = view?.canGoBack() == true
                                if (isSignedIn(url)) harvestToken()
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true

                            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                                result.confirm()
                                if (captured) return true
                                val token = message.takeUnless { it.isBlank() || it == "null" || it == "undefined" }
                                if (token == null) {
                                    // Still booting: the key is not written yet.
                                    view.postDelayed({ harvestToken() }, TOKEN_RETRY_MS)
                                    return true
                                }
                                captured = true
                                // Hidden first, so the Discord client does not flash up while leaving.
                                view.visibility = View.GONE
                                store.save(DiscordAccount(token = token))
                                // The token is now kept encrypted: the WebView's own session is removed.
                                view.stopLoading()
                                forgetDiscordWebSession()
                                navController.popBackStack()
                                return true
                            }
                        }

                        webView = this
                        // A clean start: a session left by an earlier sign-in is not reused.
                        forgetDiscordWebSession { loadUrl(LOGIN_URL) }
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
    }
}
