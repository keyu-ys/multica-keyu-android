package com.mtk.keyu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

// ───────────────────────── 解析 ─────────────────────────

internal sealed class MdBlock {
    data class Code(val lang: String, val title: String?, val text: String, val closed: Boolean) : MdBlock()
    data class Table(val aligns: List<TextAlign>, val headers: List<String>, val rows: List<List<String>>, val source: String) : MdBlock()
    data class Quote(val lines: List<String>) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class ListItem(val indent: Int, val bullet: String, val text: String, val checked: Boolean? = null) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Gallery(val items: List<MediaRef>) : MdBlock()
    data class Video(val ref: MediaRef) : MdBlock()
    data class FileCard(val name: String, val url: String) : MdBlock()
    data class RawHtml(val html: String) : MdBlock()
    data object Divider : MdBlock()
}

private val FILE_CARD_LINE = Regex("^!file\\[((?:\\\\.|[^\\]\\\\])*)]\\(([^)\\s]+)\\)$")
private val IMG_TOKEN = Regex("!\\[([^\\]]*)]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)")
private val HTML_IMG = Regex("<img\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
private val HTML_VIDEO = Regex("<video\\b[^>]*?(?:\\bsrc=[\"']([^\"']+)[\"'])?[^>]*>(?:.*?<source\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'])?", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val LINK_LINE = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)$")
private val BARE_URL_LINE = Regex("^<?(https?://\\S+?)>?$")
private val DELIM_CELL = Regex("^:?-{1,}:?$")
private val RAW_HTML_START = Regex("^<(div|table|details|section|article|svg|figure|iframe|canvas|style|form|center)\\b", RegexOption.IGNORE_CASE)

private fun nameFromUrl(url: String) = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').ifEmpty { "附件" }

/** 按 GFM 规则拆一行表格：去掉首尾竖线，`\|` 和行内代码里的竖线不算分隔。 */
private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var inCode = false
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        when {
            ch == '\\' && i + 1 < s.length && s[i + 1] == '|' -> { cur.append('|'); i++ }
            ch == '`' -> { inCode = !inCode; cur.append(ch) }
            ch == '|' && !inCode -> { cells.add(cur.toString().trim()); cur.clear() }
            else -> cur.append(ch)
        }
        i++
    }
    cells.add(cur.toString().trim())
    return cells
}

private fun isDelimRow(line: String): Boolean {
    if (!line.contains('-')) return false
    val cells = splitRow(line)
    return cells.isNotEmpty() && cells.all { DELIM_CELL.matches(it.replace(" ", "")) } && (line.contains('|') || cells.size > 1)
}

/** 一行若只由媒体组成（图片 / 视频 / 文件卡片），返回这些媒体；否则 null。 */
private fun mediaOnlyLine(t: String): List<MdBlock>? {
    FILE_CARD_LINE.find(t)?.let { m ->
        val name = m.groupValues[1].replace(Regex("\\\\(.)"), "$1")
        val url = m.groupValues[2]
        return listOf(
            when {
                looksLikeImage(name) || looksLikeImage(url) -> MdBlock.Gallery(listOf(MediaRef(url, name)))
                looksLikeVideo(name) || looksLikeVideo(url) -> MdBlock.Video(MediaRef(url, name))
                else -> MdBlock.FileCard(name, url)
            }
        )
    }
    if (t.startsWith("![")) {
        val tokens = IMG_TOKEN.findAll(t).toList()
        if (tokens.isNotEmpty() && IMG_TOKEN.replace(t, "").isBlank()) {
            return tokens.map { m ->
                val url = m.groupValues[2]
                val name = m.groupValues[1].ifBlank { nameFromUrl(url) }
                if (looksLikeVideo(url)) MdBlock.Video(MediaRef(url, name)) else MdBlock.Gallery(listOf(MediaRef(url, name)))
            }
        }
    }
    if (t.startsWith("<img", true)) {
        val tokens = HTML_IMG.findAll(t).toList()
        if (tokens.isNotEmpty() && HTML_IMG.replace(t, "").replace(Regex("</?(p|br|div|center)\\s*/?>", RegexOption.IGNORE_CASE), "").isBlank()) {
            return tokens.map { MdBlock.Gallery(listOf(MediaRef(it.groupValues[1], nameFromUrl(it.groupValues[1])))) }
        }
    }
    if (t.startsWith("<video", true)) {
        HTML_VIDEO.find(t)?.let { m ->
            val src = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (src.isNotEmpty()) return listOf(MdBlock.Video(MediaRef(src, nameFromUrl(src))))
        }
    }
    LINK_LINE.find(t)?.let { m ->
        if (looksLikeVideo(m.groupValues[2])) return listOf(MdBlock.Video(MediaRef(m.groupValues[2], m.groupValues[1])))
    }
    BARE_URL_LINE.find(t)?.let { m ->
        val url = m.groupValues[1]
        if (looksLikeVideo(url)) return listOf(MdBlock.Video(MediaRef(url, nameFromUrl(url))))
        if (looksLikeImage(url)) return listOf(MdBlock.Gallery(listOf(MediaRef(url, nameFromUrl(url)))))
    }
    return null
}

internal fun parseMarkdown(raw: String): List<MdBlock> {
    val lines = raw.replace("\r", "").lines()
    val out = mutableListOf<MdBlock>()
    fun addMedia(b: MdBlock) {
        val last = out.lastOrNull()
        if (b is MdBlock.Gallery && last is MdBlock.Gallery) out[out.lastIndex] = MdBlock.Gallery(last.items + b.items)
        else out.add(b)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        val fence = Regex("^(`{3,}|~{3,})\\s*([^\\s`]*)\\s*(.*)$").find(trimmed)
        when {
            fence != null -> {
                val marker = fence.groupValues[1]
                val lang = fence.groupValues[2].lowercase()
                val title = Regex("title=[\"']([^\"']*)[\"']").find(fence.groupValues[3])?.groupValues?.get(1)
                val buf = mutableListOf<String>()
                i++
                var closed = false
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.startsWith(marker.take(3)) && t.trimStart(marker[0]).isBlank() && t.length >= marker.length) { closed = true; i++; break }
                    buf.add(lines[i]); i++
                }
                out.add(MdBlock.Code(lang, title, buf.joinToString("\n"), closed))
            }
            trimmed.isEmpty() -> i++
            // 表格：表头行 + 分隔行（GFM），或者老格式的纯竖线行
            trimmed.contains('|') && i + 1 < lines.size && isDelimRow(lines[i + 1]) -> {
                val src = mutableListOf(lines[i], lines[i + 1])
                val headers = splitRow(trimmed)
                val aligns = splitRow(lines[i + 1]).map { c ->
                    val cc = c.replace(" ", "")
                    when {
                        cc.startsWith(":") && cc.endsWith(":") -> TextAlign.Center
                        cc.endsWith(":") -> TextAlign.End
                        else -> TextAlign.Start
                    }
                }
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    src.add(lines[i]); rows.add(splitRow(lines[i])); i++
                }
                out.add(MdBlock.Table(aligns, headers, rows, src.joinToString("\n")))
            }
            trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2 -> {
                val src = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { src.add(lines[i].trim()); i++ }
                val parsed = src.filterNot { isDelimRow(it) }.map { splitRow(it) }
                if (parsed.isNotEmpty()) out.add(MdBlock.Table(emptyList(), parsed.first(), parsed.drop(1), src.joinToString("\n")))
            }
            trimmed.startsWith(">") -> {
                val qLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    qLines.add(lines[i].trim().removePrefix(">").removePrefix(" ").trimStart('>').trim()); i++
                }
                out.add(MdBlock.Quote(qLines))
            }
            trimmed.matches(Regex("^(-\\s*){3,}$|^(\\*\\s*){3,}$|^(_\\s*){3,}$")) -> { out.add(MdBlock.Divider); i++ }
            trimmed.matches(Regex("^#{1,6}\\s+.*")) -> {
                val level = trimmed.takeWhile { it == '#' }.length
                out.add(MdBlock.Heading(level, trimmed.drop(level).trim().trimEnd('#').trim())); i++
            }
            Regex("^([-*+]|\\d+[.)])\\s+.*").matches(trimmed) -> {
                val indent = (line.length - line.trimStart().length) / 2
                val m = Regex("^([-*+]|\\d+[.)])\\s+(.*)").find(trimmed)!!
                val marker = m.groupValues[1]
                var text = m.groupValues[2]
                var checked: Boolean? = null
                Regex("^\\[([ xX])]\\s+(.*)").find(text)?.let { t -> checked = t.groupValues[1] != " "; text = t.groupValues[2] }
                val bullet = if (marker[0].isDigit()) marker.replace(')', '.') else "•"
                val media = mediaOnlyLine(text.trim())
                if (media != null) media.forEach { addMedia(it) }
                else out.add(MdBlock.ListItem(indent.coerceAtMost(4), bullet, text, checked))
                i++
            }
            RAW_HTML_START.containsMatchIn(trimmed) -> {
                val buf = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank()) { buf.add(lines[i]); i++ }
                out.add(MdBlock.RawHtml(buf.joinToString("\n")))
            }
            else -> {
                val media = mediaOnlyLine(trimmed)
                if (media != null) {
                    media.forEach { addMedia(it) }
                    i++
                } else {
                    // 段落：连续非空行合并（软换行保留成换行，和聊天里的书写习惯一致）
                    val buf = mutableListOf(trimmed)
                    i++
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        if (t.isEmpty() || t.startsWith("```") || t.startsWith("~~~") || t.startsWith(">") || Regex("^#{1,6}\\s").containsMatchIn(t) ||
                            t.startsWith("|") || Regex("^([-*+]|\\d+[.)])\\s+").containsMatchIn(t) || mediaOnlyLine(t) != null ||
                            RAW_HTML_START.containsMatchIn(t) || (t.contains('|') && i + 1 < lines.size && isDelimRow(lines[i + 1]))
                        ) break
                        buf.add(t); i++
                    }
                    out.add(MdBlock.Paragraph(buf.joinToString("\n")))
                }
            }
        }
    }
    return out
}

// ───────────────────────── 渲染 ─────────────────────────

private val sharedApiLock = Any()
@Volatile private var sharedApi: Api? = null

@Composable
private fun rememberFallbackApi(): Api {
    val ctx = LocalContext.current.applicationContext
    return remember {
        sharedApi ?: synchronized(sharedApiLock) {
            sharedApi ?: Api(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)).also { sharedApi = it }
        }
    }
}

/**
 * 消息富内容渲染（对齐 Multica 网页端 + Codex 移动端）：标题、列表/任务清单、引用、代码块（复制）、
 * 对齐表格（左右滑动/全屏/复制）、图片组（九宫格 + 全屏滑动浏览）、视频卡片、文件卡片、
 * ```html 网页预览与 ```mermaid 流程图（仅在代码块闭合后升级，流式输出时不闪）。
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, api: Api? = null, atts: List<Att> = emptyList()) {
    val realApi = api ?: rememberFallbackApi()
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> MdBlockView(block, realApi, atts) }
    }
}

@Composable
private fun MdBlockView(block: MdBlock, api: Api, atts: List<Att>) {
    when (block) {
        is MdBlock.Code -> when {
            block.closed && (block.lang == "html" || block.lang == "mermaid") && block.text.isNotBlank() ->
                RichFenceCard(block.lang, block.text, block.title)
            else -> CodeBlock(block)
        }
        is MdBlock.RawHtml -> RichFenceCard("html", block.html, null)
        is MdBlock.Table -> MdTable(block)
        is MdBlock.Gallery -> MediaGallery(api, block.items)
        is MdBlock.Video -> VideoCard(api, block.ref)
        is MdBlock.FileCard -> {
            val id = api.attachmentIdOf(block.url)
            val att = atts.firstOrNull { it.id == id } ?: Att(id ?: block.url, block.name, "", 0L)
            if (id == null) {
                Text(inline("[${block.name}](${block.url})"), fontSize = 15.sp, lineHeight = 23.sp, color = Brand.Ink)
            } else if (att.name.endsWith(".apk", true)) ApkCard(api, att) else FileAttachmentCard(api, att)
        }
        is MdBlock.Quote -> Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(8.dp)).background(Brand.SurfaceAlt).padding(end = 10.dp)
        ) {
            Box(Modifier.width(3.5.dp).fillMaxHeight().background(Brand.Lavender))
            Column(Modifier.padding(start = 10.dp, top = 7.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                block.lines.forEach { ql -> Text(inline(ql), color = Color(0xFF555C6B), fontSize = 14.5.sp, lineHeight = 21.sp) }
            }
        }
        is MdBlock.Heading -> Text(
            inline(block.text), fontWeight = FontWeight.Bold, color = Brand.Ink,
            fontSize = when (block.level) { 1 -> 19.sp; 2 -> 17.sp; else -> 15.5.sp },
            lineHeight = 24.sp, modifier = Modifier.padding(top = 6.dp)
        )
        is MdBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
            when (block.checked) {
                null -> Text(
                    if (block.bullet == "•" && block.indent > 0) "◦ " else "${block.bullet} ",
                    fontSize = 15.sp, lineHeight = 23.sp, color = Brand.Navy, fontWeight = FontWeight.SemiBold
                )
                else -> Box(
                    Modifier.padding(top = 4.dp, end = 8.dp).size(15.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (block.checked) Brand.Navy else Color.White)
                        .border(1.2.dp, if (block.checked) Brand.Navy else Brand.Subtle, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) { if (block.checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
            }
            Text(
                inline(block.text), fontSize = 15.sp, lineHeight = 23.sp,
                color = if (block.checked == true) Brand.Muted else Brand.Ink,
                textDecoration = if (block.checked == true) TextDecoration.LineThrough else null
            )
        }
        is MdBlock.Divider -> HorizontalDivider(color = Brand.Border, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
        is MdBlock.Paragraph -> Text(inline(block.text), fontSize = 15.sp, lineHeight = 23.sp, color = Brand.Ink)
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val ctx = LocalContext.current
    var codeCopied by remember(block.text) { mutableStateOf(false) }
    LaunchedEffect(codeCopied) { if (codeCopied) { delay(1400); codeCopied = false } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF4F5F8)).border(1.dp, Brand.Border, RoundedCornerShape(10.dp))
    ) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFECEEF4)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.title ?: block.lang.ifEmpty { "code" }, fontSize = 11.sp, color = Brand.Muted, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text(
                if (codeCopied) "已复制" else "复制", fontSize = 11.sp,
                color = if (codeCopied) Brand.Success else Brand.Navy, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", block.text))
                    codeCopied = true
                }.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            block.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.sp, color = Brand.InkSoft,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)
        )
    }
}

// ───────────────────────── 表格 ─────────────────────────

private class TableGeo { var colX = IntArray(0); var rowY = IntArray(0) }

/** 表格卡片：所有行共用同一套列宽（按全表最宽内容测量，单列上限可设），窄表铺满气泡宽度，宽表左右滑动。 */
@Composable
private fun MdTable(t: MdBlock.Table) {
    val ctx = LocalContext.current
    var full by remember(t.source) { mutableStateOf(false) }
    var copied by remember(t.source) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).border(1.dp, Brand.Border, shape)) {
            val avail = maxWidth
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                TableGrid(t, maxCell = 220.dp, minTotal = avail)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("${t.rows.size} 行 × ${maxOf(t.headers.size, t.rows.maxOfOrNull { it.size } ?: 0)} 列", fontSize = 10.5.sp, color = Brand.Subtle, modifier = Modifier.weight(1f))
            ChromeButton(if (copied) "已复制" else "复制表格", if (copied) Brand.Success else Brand.Muted) {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("table", t.source))
                copied = true
            }
            ChromeButton("全屏", Brand.Muted) { full = true }
        }
    }
    if (full) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("表格", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = { full = false }, modifier = Modifier.size(34.dp).clip(CircleShape).background(Brand.Surface)) {
                        Icon(Icons.Default.Close, "关闭", tint = Brand.Ink, modifier = Modifier.size(18.dp))
                    }
                }
                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    val avail = maxWidth
                    Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        Box(Modifier.clip(shape).border(1.dp, Brand.Border, shape)) { TableGrid(t, maxCell = 340.dp, minTotal = avail - 2.dp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableGrid(t: MdBlock.Table, maxCell: Dp, minTotal: Dp) {
    val colCount = maxOf(t.headers.size, t.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val all = remember(t) { listOf(t.headers) + t.rows }
    val geo = remember(t) { TableGeo() }
    Layout(
        content = {
            all.forEachIndexed { r, row ->
                for (c in 0 until colCount) {
                    Text(
                        inline(row.getOrElse(c) { "" }),
                        fontSize = 13.sp, lineHeight = 19.sp,
                        fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (r == 0) Brand.Navy else Brand.InkSoft,
                        textAlign = t.aligns.getOrElse(c) { TextAlign.Start },
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                    )
                }
            }
        },
        modifier = Modifier.drawBehind {
            if (geo.rowY.size < 2) return@drawBehind
            val w = size.width
            drawRect(Brand.LavenderSoft, Offset(0f, 0f), Size(w, geo.rowY[1].toFloat()))
            for (r in 1 until geo.rowY.size - 1) {
                val top = geo.rowY[r].toFloat()
                val h = (geo.rowY[r + 1] - geo.rowY[r]).toFloat()
                if (r % 2 == 0) drawRect(Brand.SurfaceAlt, Offset(0f, top), Size(w, h))
                drawLine(Brand.Border, Offset(0f, top), Offset(w, top), strokeWidth = 1.dp.toPx())
            }
            for (c in 1 until geo.colX.size - 1) {
                val x = geo.colX[c].toFloat()
                drawLine(Color(0xFFEFF1F5), Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.7.dp.toPx())
            }
        }
    ) { measurables, _ ->
        val minW = 64.dp.roundToPx()
        val maxW = maxCell.roundToPx()
        val colW = IntArray(colCount) { minW }
        measurables.forEachIndexed { i, m ->
            val c = i % colCount
            colW[c] = maxOf(colW[c], minOf(m.maxIntrinsicWidth(Constraints.Infinity), maxW))
        }
        val target = minTotal.roundToPx()
        val sum = colW.sum()
        if (sum < target) {
            val extra = target - sum
            for (c in 0 until colCount) colW[c] += extra / colCount + if (c < extra % colCount) 1 else 0
        }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(colW[i % colCount])) }
        val rowsN = placeables.size / colCount
        val rowY = IntArray(rowsN + 1)
        for (r in 0 until rowsN) {
            var h = 0
            for (c in 0 until colCount) h = maxOf(h, placeables[r * colCount + c].height)
            rowY[r + 1] = rowY[r] + h
        }
        val colX = IntArray(colCount + 1)
        for (c in 0 until colCount) colX[c + 1] = colX[c] + colW[c]
        geo.colX = colX; geo.rowY = rowY
        layout(colX[colCount], rowY[rowsN]) {
            placeables.forEachIndexed { i, p -> p.place(colX[i % colCount], rowY[i / colCount]) }
        }
    }
}

// ───────────────────────── 行内 ─────────────────────────

private val INLINE_RE = Regex(
    "\\*\\*\\*(.+?)\\*\\*\\*" +                      // 1 粗斜体
        "|\\*\\*(.+?)\\*\\*|__(.+?)__" +              // 2,3 粗体
        "|`([^`]+)`" +                                 // 4 行内代码
        "|~~(.+?)~~" +                                 // 5 删除线
        "|!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)" +       // 6,7 行内图片
        "|\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)" +        // 8,9 链接
        "|(?<![\\w*])\\*([^*\\n]+)\\*(?!\\*)" +       // 10 斜体
        "|(?<![\\w(\\[<\"'=])(https?://[^\\s<>()\\[\\]\"'，。、；）]+[^\\s<>()\\[\\]\"'，。、；）.,;:!?])" // 11 裸网址
)

/** 常见行内 HTML 标签转成 Markdown 等价写法；未知标签原样保留（避免误吞 List<String> 之类的文字）。 */
private fun normalizeInlineHtml(s: String): String {
    if (!s.contains('<')) return s
    var x = s
    x = x.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    x = x.replace(Regex("<(b|strong)>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "**$2**")
    x = x.replace(Regex("<(i|em)>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "*$2*")
    x = x.replace(Regex("<(code|kbd)>(.*?)</\\1>", RegexOption.IGNORE_CASE), "`$2`")
    x = x.replace(Regex("<(s|del|strike)>(.*?)</\\1>", RegexOption.IGNORE_CASE), "~~$2~~")
    x = x.replace(Regex("<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", RegexOption.IGNORE_CASE), "[$2]($1)")
    x = x.replace(Regex("</?(span|u|sup|sub|small|mark|font|p|center|ins|abbr)(\\s[^>]*)?>", RegexOption.IGNORE_CASE), "")
    x = x.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")
    return x
}

private fun isWebUrl(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true) || u.startsWith("mailto:", true)

internal fun inline(src: String): AnnotatedString = buildAnnotatedString {
    val s = normalizeInlineHtml(src)
    val linkStyle = SpanStyle(color = Brand.Navy, textDecoration = TextDecoration.Underline)
    var i = 0
    INLINE_RE.findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic, color = Brand.Ink)) { append(g[1]) }
            g[2].isNotEmpty() || g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Brand.Ink)) { append(inline(g[2].ifEmpty { g[3] })) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, background = Color(0xFFEDF0F7), color = Brand.Navy)) { append(" ${g[4]} ") }
            g[5].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Brand.Muted)) { append(g[5]) }
            g[7].isNotEmpty() -> {
                val label = "［图片${if (g[6].isNotEmpty()) "：" + g[6] else ""}］"
                if (isWebUrl(g[7])) withLink(LinkAnnotation.Url(g[7], TextLinkStyles(style = linkStyle))) { append(label) }
                else withStyle(SpanStyle(color = Brand.Muted)) { append(label) }
            }
            g[9].isNotEmpty() -> {
                val url = g[9].trim()
                if (isWebUrl(url)) withLink(LinkAnnotation.Url(url, TextLinkStyles(style = linkStyle))) { append(inline(g[8])) }
                else withStyle(linkStyle) { append(g[8]) }
            }
            g[10].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[10]) }
            g[11].isNotEmpty() -> withLink(LinkAnnotation.Url(g[11], TextLinkStyles(style = linkStyle))) { append(g[11]) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}
