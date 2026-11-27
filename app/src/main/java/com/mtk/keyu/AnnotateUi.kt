package com.mtk.keyu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class DrawTool { PEN, RECT }
private data class Mark(val tool: DrawTool, val color: Color, val points: List<Offset>)

private val MarkColors = listOf(
    Color(0xFFEF4444), // 红
    Color(0xFFF59E0B), // 琥珀
    Color(0xFFB2BEE2), // 品牌淡紫
)

/** 极简沉浸式全屏截图标注页：等比画布精准圈注 + 极简文字表达。 */
@Composable
fun AnnotateUi(shotPath: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    val shot = remember(shotPath) { shotPath?.let { File(it) }?.takeIf { it.exists() } }
    val bmp = remember(shot) { shot?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() } }

    var tool by remember { mutableStateOf(DrawTool.PEN) }
    var activeColor by remember { mutableStateOf(MarkColors[0]) }
    val marks = remember { mutableStateListOf<Mark>() }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    val statusBarTopDp = remember {
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else 0
        with(density) { px.toDp() }.coerceAtLeast(28.dp)
    }
    val navBarBottomDp = remember {
        val resId = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val px = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else 0
        with(density) { px.toDp() }.coerceAtMost(24.dp)
    }

    val indexNum = Outbox.items.size + 1

    fun flatten(): File? {
        val b = bmp?.asAndroidBitmap() ?: return null
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val c = AndroidCanvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val strokePx = (w * 0.0085f).coerceIn(6f, 16f)
        val cornerPx = (w * 0.018f).coerceIn(10f, 28f)
        val allMarks = marks + if (activePoints.size > 1) listOf(Mark(tool, activeColor, activePoints)) else emptyList()
        allMarks.forEach { mk ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mk.color.toArgb()
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            if (mk.tool == DrawTool.RECT && mk.points.size >= 2) {
                val a = mk.points.first()
                val z = mk.points.last()
                val rect = RectF(min(a.x, z.x) * w, min(a.y, z.y) * h, max(a.x, z.x) * w, max(a.y, z.y) * h)
                c.drawRoundRect(rect, cornerPx, cornerPx, paint)
            } else if (mk.points.size > 1) {
                val path = Path()
                path.moveTo(mk.points[0].x * w, mk.points[0].y * h)
                mk.points.drop(1).forEach { pt -> path.lineTo(pt.x * w, pt.y * h) }
                c.drawPath(path, paint)
            }
        }
        val dir = File(ctx.cacheDir, "shots").apply { mkdirs() }
        val f = File(dir, "annotated_${System.currentTimeMillis()}.png")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 94, it) }
        out.recycle()
        return f
    }

    fun stageCurrent(): Boolean {
        val trimmed = note.trim()
        if (bmp == null && trimmed.isEmpty()) return false
        val f = if (bmp != null) flatten() else null
        Outbox.add(f, trimmed)
        shot?.delete()
        return true
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0A0B0E))) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = statusBarTopDp + 4.dp, bottom = 6.dp, start = 14.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "取消",
                color = Color(0xFFA8B0BF),
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { shot?.delete(); onClose() }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            )
            Text(
                if (bmp != null) "截图 $indexNum" else "速记 $indexNum",
                color = Color.White,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 14.dp)
            )
            if (bmp != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    MarkColors.forEach { c ->
                        val selected = activeColor == c
                        Box(
                            Modifier
                                .size(if (selected) 21.dp else 17.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(if (selected) 2.dp else 0.dp, Color.White, CircleShape)
                                .clickable { activeColor = c }
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismissKeyboard() },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                Box(
                    Modifier
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(18.dp))
                ) {
                    Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(tool, activeColor) {
                                fun norm(pt: Offset): Offset {
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    return Offset((pt.x / w).coerceIn(0f, 1f), (pt.y / h).coerceIn(0f, 1f))
                                }
                                detectDragGestures(
                                    onDragStart = { pt -> activePoints = listOf(norm(pt)) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val n = norm(change.position)
                                        activePoints = if (tool == DrawTool.RECT && activePoints.isNotEmpty()) {
                                            listOf(activePoints.first(), n)
                                        } else {
                                            activePoints + n
                                        }
                                    },
                                    onDragEnd = {
                                        if (activePoints.size > 1) marks.add(Mark(tool, activeColor, activePoints))
                                        activePoints = emptyList()
                                    },
                                    onDragCancel = { activePoints = emptyList() }
                                )
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        val strokeW = (w * 0.009f).coerceIn(2.4.dp.toPx(), 4.2.dp.toPx())
                        val cornerR = (w * 0.018f).coerceIn(6f, 14f)
                        val all = marks + if (activePoints.size > 1) listOf(Mark(tool, activeColor, activePoints)) else emptyList()
                        all.forEach { mk ->
                            if (mk.tool == DrawTool.RECT && mk.points.size >= 2) {
                                val a = mk.points.first()
                                val z = mk.points.last()
                                drawRoundRect(
                                    color = mk.color,
                                    topLeft = Offset(min(a.x, z.x) * w, min(a.y, z.y) * h),
                                    size = Size(abs(z.x - a.x) * w, abs(z.y - a.y) * h),
                                    cornerRadius = CornerRadius(cornerR, cornerR),
                                    style = Stroke(width = strokeW)
                                )
                            } else if (mk.points.size > 1) {
                                val p = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(mk.points[0].x * w, mk.points[0].y * h)
                                    mk.points.drop(1).forEach { lineTo(it.x * w, it.y * h) }
                                }
                                drawPath(p, color = mk.color, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcons.NotePen(Color(0xFF9AA3B5), 34.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("快速记录文字", color = Color(0xFFB5BCCB), fontSize = 15.sp)
                }
            }
        }

        AnimatedVisibility(visible = bmp != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF181B22)).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    DrawToolChip("画笔", tool == DrawTool.PEN) { tool = DrawTool.PEN }
                    DrawToolChip("框选", tool == DrawTool.RECT) { tool = DrawTool.RECT }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        enabled = marks.isNotEmpty(),
                        onClick = { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) { Text("撤销", color = if (marks.isNotEmpty()) Color.White else Color(0xFF494F5C), fontSize = 13.sp) }
                    TextButton(
                        enabled = marks.isNotEmpty(),
                        onClick = { marks.clear() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) { Text("清空", color = if (marks.isNotEmpty()) Brand.Lavender else Color(0xFF494F5C), fontSize = 13.sp) }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF13151B), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = navBarBottomDp + 14.dp)
        ) {
            if (status.isNotEmpty()) {
                Text(status, color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1E212B))
                    .border(0.7.dp, Color(0xFF2D313F), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (note.isEmpty()) Text("添加说明…", color = Color(0xFF717889), fontSize = 15.sp)
                BasicTextField(
                    value = note,
                    onValueChange = { note = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(Brand.Lavender),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        if (stageCurrent()) {
                            Toast.makeText(ctx, "已暂存 (${Outbox.items.size})", Toast.LENGTH_SHORT).show()
                            onClose()
                        } else {
                            status = "请先标注或输入意见"
                        }
                    },
                    border = BorderStroke(0.9.dp, Color(0xFF393E4D)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(46.dp)
                ) { Text("暂存", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Medium) }

                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.Ink),
                    shape = RoundedCornerShape(14.dp),
                    onClick = {
                        val hasCur = bmp != null || note.isNotBlank()
                        if (hasCur) stageCurrent()
                        if (Outbox.items.isEmpty()) {
                            status = "暂无内容"
                            return@Button
                        }
                        onClose()
                        FloatPanelOverlay.showSendSheet(ctx.applicationContext)
                    },
                    modifier = Modifier.weight(1f).height(46.dp)
                ) {
                    val count = Outbox.items.size + if (bmp != null || note.isNotBlank()) 1 else 0
                    Text(if (count > 1) "发送 ($count)" else "发送", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DrawToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (selected) Brand.Ink else Color(0xFF9EA6B8),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

private typealias AndroidCanvas = android.graphics.Canvas
