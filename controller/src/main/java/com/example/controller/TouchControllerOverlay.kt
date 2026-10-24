package com.example.controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Полноценный сенсорный оверлей (Touch Controller) с аналоговым стиком WASD,
 * переключаемым виртуальным курсором мыши (MOUSE), зоной обзора камеры,
 * контекстными клавишами GLFW и режимом Drag-and-Drop редактирования.
 */
@Composable
fun TouchControllerOverlay(
    nodes: List<VirtualControlNode>,
    isEditMode: Boolean,
    selectedNodeId: String?,
    snapToGrid: Boolean,
    onSelectNode: (String) -> Unit,
    onUpdateNodePosition: (String, Float, Float) -> Unit,
    onKeyEvent: (GlfwKeyBinding, Boolean) -> Unit,
    onAnalogMove: (Float, Float) -> Unit,
    onCameraLookDelta: (Float, Float) -> Unit,
    isVirtualMouseActive: Boolean = false,
    virtualMouseNormX: Float = 0.5f,
    virtualMouseNormY: Float = 0.5f,
    onVirtualMouseDelta: (Float, Float) -> Unit = { _, _ -> },
    onVirtualMouseClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val toggledStates = remember { mutableStateMapOf<String, Boolean>() }
    val currentMouseActive by rememberUpdatedState(isVirtualMouseActive)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("touch_controller_overlay")
    ) {
        val containerWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        // Background grid when in Edit Mode, or Camera Look / Virtual Mouse Touchpad in Play Mode
        if (isEditMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stepX = size.width / 16f
                val stepY = size.height / 10f
                for (i in 1..15) {
                    drawLine(
                        color = Color(0x3338C76A),
                        start = Offset(i * stepX, 0f),
                        end = Offset(i * stepX, size.height),
                        strokeWidth = 1f
                    )
                }
                for (j in 1..9) {
                    drawLine(
                        color = Color(0x3338C76A),
                        start = Offset(0f, j * stepY),
                        end = Offset(size.width, j * stepY),
                        strokeWidth = 1f
                    )
                }
            }
        } else {
            // Free-look or Virtual Mouse touchpad across the viewport
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                if (currentMouseActive) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onVirtualMouseClick()
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (currentMouseActive) {
                                onVirtualMouseDelta(
                                    dragAmount.x / containerWidthPx,
                                    dragAmount.y / containerHeightPx
                                )
                            } else {
                                onCameraLookDelta(dragAmount.x, dragAmount.y)
                            }
                        }
                    }
            )
        }

        // Render each control node
        nodes.forEach { node ->
            val currentNode by rememberUpdatedState(node)
            val isSelected = isEditMode && selectedNodeId == node.id
            val isStickyActive = if (node.binding == GlfwKeyBinding.KEY_MOUSE_CURSOR) {
                isVirtualMouseActive
            } else {
                toggledStates[node.id] == true
            }

            val baseDp = if (node.binding == GlfwKeyBinding.ANALOG_STICK_WASD) 118.dp else 54.dp
            val scaledDp = baseDp * node.scale.coerceIn(0.5f, 1.8f)
            val nodeSizePx = with(density) { scaledDp.toPx() }

            val offsetX = (node.normalizedX * containerWidthPx - nodeSizePx / 2f)
                .coerceIn(0f, (containerWidthPx - nodeSizePx).coerceAtLeast(0f))
            val offsetY = (node.normalizedY * containerHeightPx - nodeSizePx / 2f)
                .coerceIn(0f, (containerHeightPx - nodeSizePx).coerceAtLeast(0f))

            if (node.binding == GlfwKeyBinding.ANALOG_STICK_WASD) {
                AnalogStickWidget(
                    node = node,
                    sizeDp = scaledDp,
                    isEditMode = isEditMode,
                    isSelected = isSelected,
                    offsetX = offsetX.roundToInt(),
                    offsetY = offsetY.roundToInt(),
                    onDragInEditMode = { dx, dy ->
                        val newNormX = ((currentNode.normalizedX * containerWidthPx + dx) / containerWidthPx)
                            .coerceIn(0.06f, 0.94f)
                        val newNormY = ((currentNode.normalizedY * containerHeightPx + dy) / containerHeightPx)
                            .coerceIn(0.08f, 0.92f)
                        val snappedX = if (snapToGrid) (newNormX * 32f).roundToInt() / 32f else newNormX
                        val snappedY = if (snapToGrid) (newNormY * 20f).roundToInt() / 20f else newNormY
                        onUpdateNodePosition(currentNode.id, snappedX, snappedY)
                    },
                    onSelectInEditMode = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelectNode(currentNode.id)
                    },
                    onVectorChanged = { vx, vy ->
                        onAnalogMove(vx, vy)
                    }
                )
            } else {
                ContextActionButton(
                    node = node,
                    sizeDp = scaledDp,
                    isEditMode = isEditMode,
                    isSelected = isSelected,
                    isStickyActive = isStickyActive,
                    offsetX = offsetX.roundToInt(),
                    offsetY = offsetY.roundToInt(),
                    onDragInEditMode = { dx, dy ->
                        val newNormX = ((currentNode.normalizedX * containerWidthPx + dx) / containerWidthPx)
                            .coerceIn(0.04f, 0.96f)
                        val newNormY = ((currentNode.normalizedY * containerHeightPx + dy) / containerHeightPx)
                            .coerceIn(0.06f, 0.94f)
                        val snappedX = if (snapToGrid) (newNormX * 32f).roundToInt() / 32f else newNormX
                        val snappedY = if (snapToGrid) (newNormY * 20f).roundToInt() / 20f else newNormY
                        onUpdateNodePosition(currentNode.id, snappedX, snappedY)
                    },
                    onSelectInEditMode = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelectNode(currentNode.id)
                    },
                    onTriggerKey = { pressed ->
                        if (pressed) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        if (currentNode.binding == GlfwKeyBinding.KEY_MOUSE_CURSOR) {
                            if (pressed) {
                                onKeyEvent(GlfwKeyBinding.KEY_MOUSE_CURSOR, true)
                            }
                        } else if (currentNode.isStickyToggle) {
                            if (pressed) {
                                val nextState = !(toggledStates[currentNode.id] ?: false)
                                toggledStates[currentNode.id] = nextState
                                onKeyEvent(currentNode.binding, nextState)
                            }
                        } else {
                            onKeyEvent(currentNode.binding, pressed)
                        }
                    }
                )
            }
        }

        // Virtual Mouse Cursor Pointer rendered on top when enabled
        if (isVirtualMouseActive && !isEditMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = (virtualMouseNormX * size.width).coerceIn(4f, size.width - 4f)
                val cy = (virtualMouseNormY * size.height).coerceIn(4f, size.height - 4f)

                // Subtle target reticle under the tip of the cursor
                drawCircle(
                    color = Color(0x5538C76A),
                    radius = 12f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.8f)
                )

                // Classic PC / Minecraft Java Arrow Cursor Path
                val cursorPath = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx, cy + 26f)
                    lineTo(cx + 6.8f, cy + 20.2f)
                    lineTo(cx + 11.5f, cy + 30.5f)
                    lineTo(cx + 15.8f, cy + 28.5f)
                    lineTo(cx + 11.0f, cy + 18.2f)
                    lineTo(cx + 19.5f, cy + 18.2f)
                    close()
                }

                // White fill + dark outline + emerald core accent
                drawPath(path = cursorPath, color = Color.White, style = Fill)
                drawPath(path = cursorPath, color = Color(0xFF07160E), style = Stroke(width = 2.4f))
                drawCircle(
                    color = Color(0xFF38C76A),
                    radius = 2.6f,
                    center = Offset(cx + 5f, cy + 11f)
                )
            }
        }
    }
}

@Composable
private fun AnalogStickWidget(
    node: VirtualControlNode,
    sizeDp: androidx.compose.ui.unit.Dp,
    isEditMode: Boolean,
    isSelected: Boolean,
    offsetX: Int,
    offsetY: Int,
    onDragInEditMode: (Float, Float) -> Unit,
    onSelectInEditMode: () -> Unit,
    onVectorChanged: (Float, Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var knobX by remember { mutableFloatStateOf(0f) }
    var knobY by remember { mutableFloatStateOf(0f) }

    val borderColor = when {
        isSelected -> Color(0xFFFFD84D)
        node.isPrimaryAction -> Color(0xFF38C76A)
        else -> Color(0xFF495867)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(sizeDp)
            .alpha(node.opacity)
            .clip(CircleShape)
            .background(Color(0xAA10161B))
            .border(
                width = if (isSelected) 2.5.dp else 1.5.dp,
                color = borderColor,
                shape = CircleShape
            )
            .pointerInput(isEditMode) {
                if (isEditMode) {
                    detectDragGestures(
                        onDragStart = { onSelectInEditMode() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragInEditMode(dragAmount.x, dragAmount.y)
                        }
                    )
                } else {
                    detectDragGestures(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDragEnd = {
                            knobX = 0f
                            knobY = 0f
                            onVectorChanged(0f, 0f)
                        },
                        onDragCancel = {
                            knobX = 0f
                            knobY = 0f
                            onVectorChanged(0f, 0f)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val maxRadius = size.width * 0.34f
                            val nextX = knobX + dragAmount.x
                            val nextY = knobY + dragAmount.y
                            val dist = hypot(nextX, nextY)
                            if (dist <= maxRadius) {
                                knobX = nextX
                                knobY = nextY
                            } else {
                                val angle = atan2(nextY, nextX)
                                knobX = cos(angle) * maxRadius
                                knobY = sin(angle) * maxRadius
                            }
                            onVectorChanged(
                                (knobX / maxRadius).coerceIn(-1f, 1f),
                                (-knobY / maxRadius).coerceIn(-1f, 1f)
                            )
                        }
                    )
                }
            }
            .testTag("control_node_${node.id}"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = Color(0x3338C76A),
                radius = size.minDimension * 0.38f,
                center = center,
                style = Stroke(width = 2f)
            )
            drawLine(
                color = Color(0x559AA8B6),
                start = Offset(center.x, size.height * 0.12f),
                end = Offset(center.x, size.height * 0.24f),
                strokeWidth = 2f
            )
            drawLine(
                color = Color(0x559AA8B6),
                start = Offset(center.x, size.height * 0.76f),
                end = Offset(center.x, size.height * 0.88f),
                strokeWidth = 2f
            )
            drawLine(
                color = Color(0x559AA8B6),
                start = Offset(size.width * 0.12f, center.y),
                end = Offset(size.width * 0.24f, center.y),
                strokeWidth = 2f
            )
            drawLine(
                color = Color(0x559AA8B6),
                start = Offset(size.width * 0.76f, center.y),
                end = Offset(size.width * 0.88f, center.y),
                strokeWidth = 2f
            )
            drawCircle(
                color = if (isSelected) Color(0xFFFFD84D) else Color(0xFF38C76A),
                radius = size.minDimension * 0.20f,
                center = Offset(center.x + knobX, center.y + knobY)
            )
        }
        Text(
            text = "WASD",
            color = Color(0xFFE6EDF3),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ContextActionButton(
    node: VirtualControlNode,
    sizeDp: androidx.compose.ui.unit.Dp,
    isEditMode: Boolean,
    isSelected: Boolean,
    isStickyActive: Boolean,
    offsetX: Int,
    offsetY: Int,
    onDragInEditMode: (Float, Float) -> Unit,
    onSelectInEditMode: () -> Unit,
    onTriggerKey: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor = when {
        isSelected -> Color(0xFFFFD84D)
        isStickyActive -> Color(0xFF38C76A)
        node.isPrimaryAction -> Color(0xFF38C76A)
        else -> Color(0xFF36434E)
    }
    val bgColor = when {
        isSelected -> Color(0xCC243129)
        isStickyActive -> Color(0xDD1A472E)
        else -> Color(0xBB13191F)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(sizeDp)
            .alpha(node.opacity)
            .clip(shape)
            .background(bgColor)
            .border(
                width = if (isSelected || isStickyActive) 2.dp else 1.dp,
                color = borderColor,
                shape = shape
            )
            .pointerInput(isEditMode) {
                if (isEditMode) {
                    detectDragGestures(
                        onDragStart = { onSelectInEditMode() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragInEditMode(dragAmount.x, dragAmount.y)
                        }
                    )
                } else {
                    detectTapGestures(
                        onPress = {
                            onTriggerKey(true)
                            tryAwaitRelease()
                            onTriggerKey(false)
                        }
                    )
                }
            }
            .testTag("control_node_${node.id}"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(2.dp)
        ) {
            Text(
                text = node.binding.shortLabel,
                color = if (isSelected) {
                    Color(0xFFFFD84D)
                } else if (isStickyActive && node.binding == GlfwKeyBinding.KEY_MOUSE_CURSOR) {
                    Color(0xFF38C76A)
                } else {
                    Color(0xFFF0F6FC)
                },
                fontSize = (10.5f * node.scale.coerceIn(0.8f, 1.3f)).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (node.binding == GlfwKeyBinding.KEY_MOUSE_CURSOR) {
                Text(
                    text = if (isStickyActive) "КУРСОР" else "ПРИЦЕЛ",
                    color = if (isStickyActive) Color(0xFF38C76A) else Color(0xFF9AA8B6),
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            } else if (node.isStickyToggle) {
                Text(
                    text = if (isStickyActive) "HOLD" else "TOG",
                    color = if (isStickyActive) Color(0xFF38C76A) else Color(0xFF7D8B99),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
