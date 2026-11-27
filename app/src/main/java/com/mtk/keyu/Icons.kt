package com.mtk.keyu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 极简克制单色线性图标集：统一 1.65dp 圆头描边，纯极客原生质感。 */
object AppIcons {
    @Composable
    fun MenuLines(tint: Color = Brand.Ink, size: Dp = 21.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.75.dp.toPx()
            val y1 = this.size.height * 0.35f
            val y2 = this.size.height * 0.65f
            drawLine(tint, Offset(this.size.width * 0.14f, y1), Offset(this.size.width * 0.86f, y1), sw, StrokeCap.Round)
            drawLine(tint, Offset(this.size.width * 0.14f, y2), Offset(this.size.width * 0.62f, y2), sw, StrokeCap.Round)
        }
    }

    @Composable
    fun NewChat(tint: Color = Brand.Ink, size: Dp = 20.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.65.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.54f, h * 0.18f)
                lineTo(w * 0.25f, h * 0.18f)
                quadraticTo(w * 0.15f, h * 0.18f, w * 0.15f, h * 0.28f)
                lineTo(w * 0.15f, h * 0.75f)
                quadraticTo(w * 0.15f, h * 0.85f, w * 0.25f, h * 0.85f)
                lineTo(w * 0.72f, h * 0.85f)
                quadraticTo(w * 0.82f, h * 0.85f, w * 0.82f, h * 0.75f)
                lineTo(w * 0.82f, h * 0.46f)
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(tint, Offset(w * 0.45f, h * 0.55f), Offset(w * 0.84f, h * 0.16f), sw, StrokeCap.Round)
        }
    }

    @Composable
    fun Sliders(tint: Color = Brand.InkSoft, size: Dp = 19.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val r = w * 0.09f
            fun row(yRatio: Float, knobRatio: Float) {
                val y = h * yRatio
                val kx = w * knobRatio
                drawLine(tint, Offset(w * 0.14f, y), Offset((kx - r - 1.5f).coerceAtLeast(w * 0.14f), y), sw, StrokeCap.Round)
                drawLine(tint, Offset((kx + r + 1.5f).coerceAtMost(w * 0.86f), y), Offset(w * 0.86f, y), sw, StrokeCap.Round)
                drawCircle(tint, r, Offset(kx, y), style = Stroke(sw))
            }
            row(0.27f, 0.36f)
            row(0.50f, 0.68f)
            row(0.73f, 0.44f)
        }
    }

    @Composable
    fun FloatOrb(tint: Color = Brand.Ink, filledCenter: Boolean = true, size: Dp = 20.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.65.dp.toPx()
            val w = this.size.width; val h = this.size.height
            drawCircle(tint, w * 0.36f, Offset(w * 0.5f, h * 0.5f), style = Stroke(sw))
            if (filledCenter) {
                drawCircle(tint, w * 0.14f, Offset(w * 0.5f, h * 0.5f))
            } else {
                drawCircle(tint, w * 0.11f, Offset(w * 0.5f, h * 0.5f), style = Stroke(sw * 0.85f))
            }
        }
    }

    @Composable
    fun Bell(tint: Color = Brand.InkSoft, size: Dp = 19.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.25f, h * 0.68f)
                lineTo(w * 0.25f, h * 0.44f)
                cubicTo(w * 0.25f, h * 0.22f, w * 0.75f, h * 0.22f, w * 0.75f, h * 0.44f)
                lineTo(w * 0.75f, h * 0.68f)
                lineTo(w * 0.83f, h * 0.75f)
                lineTo(w * 0.17f, h * 0.75f)
                close()
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(tint, Offset(w * 0.43f, h * 0.86f), Offset(w * 0.57f, h * 0.86f), sw, StrokeCap.Round)
        }
    }

    @Composable
    fun PackageBox(tint: Color = Brand.InkSoft, size: Dp = 19.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.5f, h * 0.15f)
                lineTo(w * 0.83f, h * 0.32f)
                lineTo(w * 0.83f, h * 0.68f)
                lineTo(w * 0.5f, h * 0.85f)
                lineTo(w * 0.17f, h * 0.68f)
                lineTo(w * 0.17f, h * 0.32f)
                close()
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(tint, Offset(w * 0.17f, h * 0.32f), Offset(w * 0.5f, h * 0.50f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.83f, h * 0.32f), Offset(w * 0.5f, h * 0.50f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.5f, h * 0.50f), Offset(w * 0.5f, h * 0.85f), sw, StrokeCap.Round)
        }
    }

    @Composable
    fun BubbleChat(tint: Color = Brand.InkSoft, size: Dp = 19.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.24f, h * 0.74f)
                lineTo(w * 0.17f, h * 0.84f)
                lineTo(w * 0.35f, h * 0.76f)
                lineTo(w * 0.74f, h * 0.76f)
                quadraticTo(w * 0.84f, h * 0.76f, w * 0.84f, h * 0.65f)
                lineTo(w * 0.84f, h * 0.29f)
                quadraticTo(w * 0.84f, h * 0.18f, w * 0.74f, h * 0.18f)
                lineTo(w * 0.26f, h * 0.18f)
                quadraticTo(w * 0.16f, h * 0.18f, w * 0.16f, h * 0.29f)
                lineTo(w * 0.16f, h * 0.65f)
                quadraticTo(w * 0.16f, h * 0.74f, w * 0.24f, h * 0.74f)
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun ShieldCheck(tint: Color = Brand.InkSoft, size: Dp = 19.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.5f, h * 0.15f)
                lineTo(w * 0.81f, h * 0.26f)
                lineTo(w * 0.81f, h * 0.52f)
                cubicTo(w * 0.81f, h * 0.71f, w * 0.66f, h * 0.81f, w * 0.5f, h * 0.87f)
                cubicTo(w * 0.34f, h * 0.81f, w * 0.19f, h * 0.71f, w * 0.19f, h * 0.52f)
                lineTo(w * 0.19f, h * 0.26f)
                close()
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun Bolt(tint: Color = Brand.InkSoft, size: Dp = 19.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.56f, h * 0.15f)
                lineTo(w * 0.25f, h * 0.54f)
                lineTo(w * 0.49f, h * 0.54f)
                lineTo(w * 0.44f, h * 0.85f)
                lineTo(w * 0.75f, h * 0.46f)
                lineTo(w * 0.51f, h * 0.46f)
                close()
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun ServerLink(tint: Color = Brand.InkSoft, size: Dp = 19.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            drawRoundRect(tint, Offset(w * 0.16f, h * 0.21f), Size(w * 0.68f, h * 0.23f), CornerRadius(6f, 6f), Stroke(sw))
            drawRoundRect(tint, Offset(w * 0.16f, h * 0.56f), Size(w * 0.68f, h * 0.23f), CornerRadius(6f, 6f), Stroke(sw))
            drawCircle(tint, sw * 0.65f, Offset(w * 0.30f, h * 0.325f))
            drawCircle(tint, sw * 0.65f, Offset(w * 0.30f, h * 0.675f))
        }
    }

    @Composable
    fun Search(tint: Color = Brand.Muted, size: Dp = 16.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            drawCircle(tint, w * 0.29f, Offset(w * 0.44f, h * 0.44f), style = Stroke(sw))
            drawLine(tint, Offset(w * 0.66f, h * 0.66f), Offset(w * 0.85f, h * 0.85f), sw, StrokeCap.Round)
        }
    }

    @Composable
    fun ScreenshotMark(tint: Color = Color.White, size: Dp = 21.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.7.dp.toPx()
            val w = this.size.width; val h = this.size.height
            drawLine(tint, Offset(w * 0.18f, h * 0.36f), Offset(w * 0.18f, h * 0.18f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.18f, h * 0.18f), Offset(w * 0.36f, h * 0.18f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.64f, h * 0.18f), Offset(w * 0.82f, h * 0.18f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.82f, h * 0.18f), Offset(w * 0.82f, h * 0.36f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.18f, h * 0.64f), Offset(w * 0.18f, h * 0.82f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.18f, h * 0.82f), Offset(w * 0.36f, h * 0.82f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.64f, h * 0.82f), Offset(w * 0.82f, h * 0.82f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.82f, h * 0.82f), Offset(w * 0.82f, h * 0.64f), sw, StrokeCap.Round)
            drawCircle(tint, w * 0.12f, Offset(w * 0.5f, h * 0.5f), style = Stroke(sw))
        }
    }

    @Composable
    fun NotePen(tint: Color = Color.White, size: Dp = 21.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.7.dp.toPx()
            val w = this.size.width; val h = this.size.height
            drawRoundRect(tint, Offset(w * 0.34f, h * 0.15f), Size(w * 0.32f, h * 0.43f), CornerRadius(w * 0.16f, w * 0.16f), Stroke(sw))
            val arc = Path().apply {
                moveTo(w * 0.22f, h * 0.48f)
                cubicTo(w * 0.22f, h * 0.74f, w * 0.78f, h * 0.74f, w * 0.78f, h * 0.48f)
            }
            drawPath(arc, tint, style = Stroke(sw, cap = StrokeCap.Round))
            drawLine(tint, Offset(w * 0.5f, h * 0.70f), Offset(w * 0.5f, h * 0.85f), sw, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.36f, h * 0.85f), Offset(w * 0.64f, h * 0.85f), sw, StrokeCap.Round)
        }
    }

    @Composable
    fun PaperPlane(tint: Color = Color.White, size: Dp = 20.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.7.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.84f, h * 0.18f)
                lineTo(w * 0.16f, h * 0.46f)
                lineTo(w * 0.46f, h * 0.56f)
                lineTo(w * 0.56f, h * 0.84f)
                close()
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(tint, Offset(w * 0.46f, h * 0.56f), Offset(w * 0.82f, h * 0.20f), sw, StrokeCap.Round)
        }
    }

    @Composable
    fun Download(tint: Color = Brand.InkSoft, size: Dp = 19.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.65.dp.toPx()
            val w = this.size.width; val h = this.size.height
            // 竖轴 + 向下箭头
            drawLine(tint, Offset(w * 0.50f, h * 0.16f), Offset(w * 0.50f, h * 0.64f), sw, StrokeCap.Round)
            val arrow = Path().apply {
                moveTo(w * 0.30f, h * 0.46f)
                lineTo(w * 0.50f, h * 0.66f)
                lineTo(w * 0.70f, h * 0.46f)
            }
            drawPath(arrow, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // 极简底部托盘
            val tray = Path().apply {
                moveTo(w * 0.18f, h * 0.72f)
                lineTo(w * 0.18f, h * 0.78f)
                quadraticTo(w * 0.18f, h * 0.84f, w * 0.25f, h * 0.84f)
                lineTo(w * 0.75f, h * 0.84f)
                quadraticTo(w * 0.82f, h * 0.84f, w * 0.82f, h * 0.78f)
                lineTo(w * 0.82f, h * 0.72f)
            }
            drawPath(tray, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun ChevronRight(tint: Color = Brand.Subtle, size: Dp = 17.dp) {
        Canvas(Modifier.size(size)) {
            val sw = 1.6.dp.toPx()
            val w = this.size.width; val h = this.size.height
            val p = Path().apply {
                moveTo(w * 0.38f, h * 0.24f)
                lineTo(w * 0.64f, h * 0.50f)
                lineTo(w * 0.38f, h * 0.76f)
            }
            drawPath(p, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
