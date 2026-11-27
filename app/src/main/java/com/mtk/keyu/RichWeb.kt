package com.mtk.keyu

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** 渲染过的高度缓存：列表回滚时直接占好位置，不跳动。 */
private val webHeightCache = mutableMapOf<Int, Int>()

private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private const val HEIGHT_BRIDGE = """<script>(function(){function r(){try{var b=document.body,d=document.documentElement;MulticaBridge.h(Math.ceil(Math.max(b?b.scrollHeight:0,d?d.scrollHeight:0,b?b.getBoundingClientRect().height:0)))}catch(e){}}
window.addEventListener('load',r);if(window.ResizeObserver){try{new ResizeObserver(r).observe(document.documentElement)}catch(e){}}setTimeout(r,60);setTimeout(r,400);setTimeout(r,1500);setTimeout(r,4000)})();</script>"""

private const val BASE_STYLE = """<style>html,body{margin:0;padding:0;background:#fff;color:#0c0d10;font-family:-apple-system,system-ui,'PingFang SC',sans-serif;font-size:14px;line-height:1.55;word-wrap:break-word}body{padding:10px 12px}img,video,svg,canvas{max-width:100%;height:auto}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #e8eaef;padding:4px 8px}pre{white-space:pre-wrap}</style>"""

/** 把智能体给的 HTML 片段 / 整页包成可在手机上正确缩放、能回报高度的文档。 */
internal fun wrapHtmlDocument(html: String): String {
    val viewport = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=5\">"
    val isFull = Regex("<html[\\s>]|<!doctype", RegexOption.IGNORE_CASE).containsMatchIn(html)
    if (!isFull) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">$viewport$BASE_STYLE</head><body>$html$HEIGHT_BRIDGE</body></html>"
    }
    var doc = html
    if (!Regex("name=[\"']viewport", RegexOption.IGNORE_CASE).containsMatchIn(doc)) {
        doc = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(doc)?.let { m ->
            doc.substring(0, m.range.last + 1) + viewport + doc.substring(m.range.last + 1)
        } ?: (viewport + doc)
    }
    val bodyEnd = doc.lastIndexOf("</body>", ignoreCase = true)
    return if (bodyEnd >= 0) doc.substring(0, bodyEnd) + HEIGHT_BRIDGE + doc.substring(bodyEnd) else doc + HEIGHT_BRIDGE
}

/** Mermaid：先走国内 npmmirror，失败再试 jsdelivr / unpkg；语法错误直接显示原因。 */
internal fun mermaidDocument(src: String): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=6">
<style>html,body{margin:0;background:#fff;font-family:system-ui,sans-serif}#c{padding:12px;display:flex;justify-content:center}#c svg{max-width:100%;height:auto}#e{color:#d63b30;font-size:13px;padding:12px;white-space:pre-wrap}#l{color:#7b818e;font-size:13px;padding:16px;text-align:center}</style></head>
<body><div id="l">正在绘制流程图…</div><div id="c"></div><div id="e"></div><pre id="s" style="display:none">${escapeHtml(src)}</pre>
<script>
var urls=["https://registry.npmmirror.com/mermaid/11.4.1/files/dist/mermaid.min.js","https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js","https://unpkg.com/mermaid@11.4.1/dist/mermaid.min.js"];
function load(i){if(i>=urls.length){done('流程图引擎加载失败，请检查网络后重试');return}var s=document.createElement('script');s.src=urls[i];s.onload=draw;s.onerror=function(){load(i+1)};document.head.appendChild(s)}
function done(err){document.getElementById('l').style.display='none';if(err)document.getElementById('e').textContent=err;setTimeout(function(){try{MulticaBridge.h(Math.ceil(document.body.scrollHeight))}catch(e){}},30)}
function draw(){try{mermaid.initialize({startOnLoad:false,theme:'neutral',securityLevel:'strict',fontFamily:'system-ui,sans-serif'});mermaid.render('mm'+Date.now(),document.getElementById('s').textContent).then(function(r){document.getElementById('c').innerHTML=r.svg;done()}).catch(function(e){done('流程图语法有误：'+(e&&e.message?e.message:e))})}catch(e){done('流程图语法有误：'+e.message)}}
load(0);
</script></body></html>"""

private class HeightBridge(private val onHeight: (Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun h(px: Int) { main.post { onHeight(px) } }
}

/** 沙箱 WebView：无本地文件/内容访问、不允许页面内跳转（用户点链接时交给系统浏览器）。 */
@SuppressLint("SetJavaScriptEnabled")
private fun sandboxWebView(ctx: Context, zoomable: Boolean, onHeight: ((Int) -> Unit)?): WebView = WebView(ctx).apply {
    setBackgroundColor(AColor.WHITE)
    isVerticalScrollBarEnabled = zoomable
    isHorizontalScrollBarEnabled = false
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(zoomable)
        builtInZoomControls = zoomable
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = true
    }
    if (onHeight != null) addJavascriptInterface(HeightBridge(onHeight), "MulticaBridge")
    else addJavascriptInterface(HeightBridge {}, "MulticaBridge")
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (request.hasGesture() && (u.scheme == "http" || u.scheme == "https" || u.scheme == "mailto")) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return true
        }
    }
}

private const val BASE_URL = "https://preview.multica.local/"

/**
 * 富代码块卡片（与网页端一致的两种：```html 网页预览、```mermaid 流程图）。
 * 标题栏常驻：类型/标题 · 预览|源码 切换 · 复制 · 全屏。预览高度跟随内容，最高 520dp，更长的点全屏看。
 */
@Composable
fun RichFenceCard(kind: String, body: String, title: String?) {
    val ctx = LocalContext.current
    val isMermaid = kind == "mermaid"
    val doc = remember(body, kind) { if (isMermaid) mermaidDocument(body) else wrapHtmlDocument(body) }
    val key = remember(doc) { doc.hashCode() }
    var showSource by remember(key) { mutableStateOf(false) }
    var heightDp by remember(key) { mutableIntStateOf(webHeightCache[key] ?: 0) }
    var full by remember(key) { mutableStateOf(false) }
    var copied by remember(key) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    val shape = RoundedCornerShape(12.dp)

    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, Brand.Border, shape).background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF4F5F8)).padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title?.takeIf { it.isNotBlank() } ?: if (isMermaid) "流程图" else "HTML 预览",
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Brand.InkSoft,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Row(Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFE6E8EF)).padding(2.dp)) {
                listOf(false to "预览", true to "源码").forEach { (src, label) ->
                    Text(
                        label, fontSize = 11.sp,
                        color = if (showSource == src) Brand.Navy else Brand.Muted,
                        fontWeight = if (showSource == src) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp))
                            .background(if (showSource == src) Color.White else Color.Transparent)
                            .clickable { showSource = src }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            ChromeButton(if (copied) "已复制" else "复制", if (copied) Brand.Success else Brand.Navy) {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(kind, body))
                copied = true
            }
            ChromeButton("全屏", Brand.Navy) { full = true }
        }
        if (showSource) {
            Text(
                body, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = Brand.InkSoft,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(12.dp)
            )
        } else {
            val h = if (heightDp > 0) heightDp.coerceIn(48, 520) else 140
            Box(Modifier.fillMaxWidth().height(h.dp)) {
                AndroidView(
                    factory = { c ->
                        sandboxWebView(c, zoomable = false) { px ->
                            if (px > 0) { heightDp = px + 2; webHeightCache[key] = px + 2 }
                        }.also { it.loadDataWithBaseURL(BASE_URL, doc, "text/html", "utf-8", null) }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize()
                )
                if (heightDp == 0) CircularProgressIndicator(Modifier.align(Alignment.Center).size(18.dp), strokeWidth = 2.dp, color = Brand.Lavender)
                if (heightDp > 520) {
                    Text(
                        "内容较长 · 全屏查看", fontSize = 11.sp, color = Brand.Navy, fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(10.dp)).background(Color(0xEEFFFFFF)).border(0.6.dp, Brand.Border, RoundedCornerShape(10.dp))
                            .clickable { full = true }.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
    if (full) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title?.takeIf { it.isNotBlank() } ?: if (isMermaid) "流程图" else "HTML 预览", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = { full = false }, modifier = Modifier.size(34.dp).clip(CircleShape).background(Brand.Surface)) {
                        Icon(Icons.Default.Close, "关闭", tint = Brand.Ink, modifier = Modifier.size(18.dp))
                    }
                }
                AndroidView(
                    factory = { c -> sandboxWebView(c, zoomable = true, onHeight = null).also { it.loadDataWithBaseURL(BASE_URL, doc, "text/html", "utf-8", null) } },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
internal fun ChromeButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Suppress("unused")
private fun Uri.isWeb() = scheme == "http" || scheme == "https"
