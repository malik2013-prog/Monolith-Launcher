package com.example.skineditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Интерактивный 2D UV-редактор развертки скина (64x64 и 64x32) с поддержкой рисования,
 * заливки, пипетки, ластика и разметки зон тела (Head, Body, Arms, Legs, Overlay).
 */
@Composable
fun SkinUvCanvas2D(
    pixels: IntArray,
    format: SkinFormat,
    showGridLines: Boolean,
    showBodyZoneGuides: Boolean,
    onPixelTouch: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentFormat by rememberUpdatedState(format)
    val currentOnTouch by rememberUpdatedState(onPixelTouch)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0E1318))
            .border(1.dp, Color(0xFF28333E), RoundedCornerShape(10.dp))
            .pointerInput(format) {
                detectTapGestures { pos ->
                    val cellW = size.width / currentFormat.width.toFloat()
                    val cellH = size.height / currentFormat.height.toFloat()
                    val px = floor(pos.x / cellW).toInt().coerceIn(0, currentFormat.width - 1)
                    val py = floor(pos.y / cellH).toInt().coerceIn(0, currentFormat.height - 1)
                    currentOnTouch(px, py)
                }
            }
            .pointerInput(format) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val pos = change.position
                    val cellW = size.width / currentFormat.width.toFloat()
                    val cellH = size.height / currentFormat.height.toFloat()
                    val px = floor(pos.x / cellW).toInt().coerceIn(0, currentFormat.width - 1)
                    val py = floor(pos.y / cellH).toInt().coerceIn(0, currentFormat.height - 1)
                    currentOnTouch(px, py)
                }
            }
            .testTag("skin_uv_2d_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellW = size.width / format.width.toFloat()
            val cellH = size.height / format.height.toFloat()

            // Checkerboard background + pixel buffer
            for (y in 0 until format.height) {
                for (x in 0 until format.width) {
                    val checkerColor = if ((x + y) % 2 == 0) Color(0xFF161D24) else Color(0xFF12181E)
                    drawRect(
                        color = checkerColor,
                        topLeft = Offset(x * cellW, y * cellH),
                        size = Size(cellW + 0.4f, cellH + 0.4f)
                    )
                    val idx = y * format.width + x
                    if (idx in pixels.indices) {
                        val argb = pixels[idx]
                        val alpha = (argb ushr 24) and 0xFF
                        if (alpha > 0) {
                            drawRect(
                                color = Color(argb),
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f)
                            )
                        }
                    }
                }
            }

            if (showGridLines) {
                for (x in 0..format.width step 4) {
                    drawLine(
                        color = if (x % 8 == 0) Color(0x339AA8B6) else Color(0x169AA8B6),
                        start = Offset(x * cellW, 0f),
                        end = Offset(x * cellW, size.height),
                        strokeWidth = 1f
                    )
                }
                for (y in 0..format.height step 4) {
                    drawLine(
                        color = if (y % 8 == 0) Color(0x339AA8B6) else Color(0x169AA8B6),
                        start = Offset(0f, y * cellH),
                        end = Offset(size.width, y * cellH),
                        strokeWidth = 1f
                    )
                }
            }

            if (showBodyZoneGuides) {
                // Head base outline
                drawRect(
                    color = Color(0xAA38C76A),
                    topLeft = Offset(8 * cellW, 8 * cellH),
                    size = Size(8 * cellW, 8 * cellH),
                    style = Stroke(width = 1.5f)
                )
                // Hat overlay outline
                drawRect(
                    color = Color(0xAAFFD84D),
                    topLeft = Offset(40 * cellW, 8 * cellH),
                    size = Size(8 * cellW, 8 * cellH),
                    style = Stroke(width = 1.5f)
                )
                // Torso outline
                drawRect(
                    color = Color(0xAA38C76A),
                    topLeft = Offset(20 * cellW, 20 * cellH),
                    size = Size(8 * cellW, 12 * cellH),
                    style = Stroke(width = 1.5f)
                )
            }
        }
    }
}

/**
 * Интерактивный 3D-превьюер модели игрока (Steve / Alex) с поддержкой вращения,
 * отображения второго слоя (Outer Overlay) и анимации походки.
 */
@Composable
fun SkinPreview3DViewport(
    pixels: IntArray,
    format: SkinFormat,
    yawDegrees: Float,
    pitchDegrees: Float,
    showOuterOverlay: Boolean,
    walkPhase: Float,
    onRotateDelta: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F1419))
            .border(1.dp, Color(0xFF28333E), RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onRotateDelta(dragAmount.x * 0.65f, dragAmount.y * 0.45f)
                }
            }
            .testTag("skin_3d_preview_viewport")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Subtle floor platform
            val cx = size.width / 2f
            val cy = size.height * 0.50f
            val scale = size.minDimension * 0.032f

            drawOval(
                color = Color(0x2838C76A),
                topLeft = Offset(cx - scale * 7f, cy + scale * 13.5f),
                size = Size(scale * 14f, scale * 4f)
            )

            val yawRad = Math.toRadians(yawDegrees.toDouble()).toFloat()
            val swingAngle = sin(walkPhase) * 0.35f

            // Render 3D character parts in back-to-front depth order
            val facingFront = cos(yawRad) >= 0f

            // Left & Right Legs
            drawTexturedPartFace(
                pixels = pixels,
                format = format,
                uvX = if (facingFront) 4 else 12,
                uvY = 20,
                uvW = 4,
                uvH = 12,
                centerX = cx - scale * 2.1f * cos(yawRad),
                centerY = cy + scale * 8f + sin(swingAngle) * scale * 1.2f,
                scaleX = scale * 0.95f,
                scaleY = scale * 0.95f,
                skewY = sin(yawRad) * 0.18f
            )
            val leftLegUvX = if (format == SkinFormat.MODERN_64X64) (if (facingFront) 20 else 28) else (if (facingFront) 4 else 12)
            val leftLegUvY = if (format == SkinFormat.MODERN_64X64) 52 else 20
            drawTexturedPartFace(
                pixels = pixels,
                format = format,
                uvX = leftLegUvX,
                uvY = leftLegUvY,
                uvW = 4,
                uvH = 12,
                centerX = cx + scale * 2.1f * cos(yawRad),
                centerY = cy + scale * 8f - sin(swingAngle) * scale * 1.2f,
                scaleX = scale * 0.95f,
                scaleY = scale * 0.95f,
                skewY = sin(yawRad) * 0.18f
            )

            // Torso Base + Jacket Overlay
            drawTexturedPartFace(
                pixels = pixels,
                format = format,
                uvX = if (facingFront) 20 else 32,
                uvY = 20,
                uvW = 8,
                uvH = 12,
                centerX = cx,
                centerY = cy - scale * 2.8f,
                scaleX = scale,
                scaleY = scale,
                skewY = sin(yawRad) * 0.16f
            )
            if (showOuterOverlay && format == SkinFormat.MODERN_64X64) {
                drawTexturedPartFace(
                    pixels = pixels,
                    format = format,
                    uvX = if (facingFront) 20 else 32,
                    uvY = 36,
                    uvW = 8,
                    uvH = 12,
                    centerX = cx,
                    centerY = cy - scale * 2.8f,
                    scaleX = scale * 1.06f,
                    scaleY = scale * 1.06f,
                    skewY = sin(yawRad) * 0.16f
                )
            }

            // Right & Left Arms
            drawTexturedPartFace(
                pixels = pixels,
                format = format,
                uvX = if (facingFront) 44 else 52,
                uvY = 20,
                uvW = 4,
                uvH = 12,
                centerX = cx - scale * 6.0f * cos(yawRad),
                centerY = cy - scale * 2.6f - sin(swingAngle) * scale * 1.4f,
                scaleX = scale * 0.95f,
                scaleY = scale * 0.95f,
                skewY = sin(yawRad) * 0.18f
            )
            val leftArmUvX = if (format == SkinFormat.MODERN_64X64) (if (facingFront) 36 else 44) else (if (facingFront) 44 else 52)
            val leftArmUvY = if (format == SkinFormat.MODERN_64X64) 52 else 20
            drawTexturedPartFace(
                pixels = pixels,
                format = format,
                uvX = leftArmUvX,
                uvY = leftArmUvY,
                uvW = 4,
                uvH = 12,
                centerX = cx + scale * 6.0f * cos(yawRad),
                centerY = cy - scale * 2.6f + sin(swingAngle) * scale * 1.4f,
                scaleX = scale * 0.95f,
                scaleY = scale * 0.95f,
                skewY = sin(yawRad) * 0.18f
            )

            // Head Base + Side perspective
            val headCenterY = cy - scale * 12.6f + (pitchDegrees * 0.15f)
            drawTexturedPartFace(
                pixels = pixels,
                format = format,
                uvX = if (facingFront) 8 else 24,
                uvY = 8,
                uvW = 8,
                uvH = 8,
                centerX = cx,
                centerY = headCenterY,
                scaleX = scale * 1.05f,
                scaleY = scale * 1.05f,
                skewY = sin(yawRad) * 0.18f
            )

            // Outer Hat Overlay (UV 40,8)
            if (showOuterOverlay) {
                drawTexturedPartFace(
                    pixels = pixels,
                    format = format,
                    uvX = if (facingFront) 40 else 56,
                    uvY = 8,
                    uvW = 8,
                    uvH = 8,
                    centerX = cx,
                    centerY = headCenterY,
                    scaleX = scale * 1.14f,
                    scaleY = scale * 1.14f,
                    skewY = sin(yawRad) * 0.18f
                )
            }
        }
    }
}

private fun DrawScope.drawTexturedPartFace(
    pixels: IntArray,
    format: SkinFormat,
    uvX: Int,
    uvY: Int,
    uvW: Int,
    uvH: Int,
    centerX: Float,
    centerY: Float,
    scaleX: Float,
    scaleY: Float,
    skewY: Float
) {
    val startX = centerX - (uvW * scaleX) / 2f
    val startY = centerY - (uvH * scaleY) / 2f

    for (py in 0 until uvH) {
        for (px in 0 until uvW) {
            val tx = (uvX + px).coerceIn(0, format.width - 1)
            val ty = (uvY + py).coerceIn(0, format.height - 1)
            val idx = ty * format.width + tx
            if (idx in pixels.indices) {
                val argb = pixels[idx]
                val alpha = (argb ushr 24) and 0xFF
                if (alpha > 10) {
                    val x0 = startX + px * scaleX
                    val y0 = startY + py * scaleY + (px - uvW / 2f) * scaleX * skewY
                    val path = Path().apply {
                        moveTo(x0, y0)
                        lineTo(x0 + scaleX + 0.6f, y0 + scaleX * skewY)
                        lineTo(x0 + scaleX + 0.6f, y0 + scaleY + 0.6f + scaleX * skewY)
                        lineTo(x0, y0 + scaleY + 0.6f)
                        close()
                    }
                    drawPath(path = path, color = Color(argb))
                }
            }
        }
    }
}
