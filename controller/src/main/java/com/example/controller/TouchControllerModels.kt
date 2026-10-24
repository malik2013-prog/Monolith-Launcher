package com.example.controller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class GlfwKeyBinding(val glfwCode: Int, val shortLabel: String, val keyName: String) {
    KEY_JUMP(32, "JUMP", "GLFW_KEY_SPACE"),
    KEY_SNEAK(340, "SNEAK", "GLFW_KEY_LEFT_SHIFT"),
    MOUSE_BREAK(0, "BREAK", "GLFW_MOUSE_BUTTON_LEFT"),
    MOUSE_PLACE(1, "PLACE", "GLFW_MOUSE_BUTTON_RIGHT"),
    KEY_INVENTORY(69, "INV", "GLFW_KEY_E"),
    KEY_CHAT(84, "CHAT", "GLFW_KEY_T"),
    KEY_DEBUG_F3(292, "F3", "GLFW_KEY_F3"),
    KEY_THIRD_PERSON_F5(294, "F5", "GLFW_KEY_F5"),
    KEY_PLAYER_LIST_TAB(258, "TAB", "GLFW_KEY_TAB"),
    KEY_PAUSE_ESC(256, "ESC", "GLFW_KEY_ESCAPE"),
    ANALOG_STICK_WASD(999, "WASD", "ANALOG_AXIS_WASD")
}

data class VirtualControlNode(
    val id: String,
    val binding: GlfwKeyBinding,
    val normalizedX: Float,
    val normalizedY: Float,
    val scale: Float = 1.0f,
    val opacity: Float = 0.82f,
    val isStickyToggle: Boolean = false,
    val isPrimaryAction: Boolean = false
)

object ControllerProfileStorage {

    fun defaultLandscapeLayout(): List<VirtualControlNode> = listOf(
        VirtualControlNode(
            id = "stick_wasd",
            binding = GlfwKeyBinding.ANALOG_STICK_WASD,
            normalizedX = 0.14f,
            normalizedY = 0.68f,
            scale = 1.15f,
            opacity = 0.88f,
            isPrimaryAction = true
        ),
        VirtualControlNode(
            id = "btn_jump",
            binding = GlfwKeyBinding.KEY_JUMP,
            normalizedX = 0.88f,
            normalizedY = 0.68f,
            scale = 1.12f,
            opacity = 0.90f,
            isPrimaryAction = true
        ),
        VirtualControlNode(
            id = "btn_sneak",
            binding = GlfwKeyBinding.KEY_SNEAK,
            normalizedX = 0.77f,
            normalizedY = 0.78f,
            scale = 0.98f,
            opacity = 0.85f,
            isStickyToggle = true
        ),
        VirtualControlNode(
            id = "btn_break",
            binding = GlfwKeyBinding.MOUSE_BREAK,
            normalizedX = 0.77f,
            normalizedY = 0.46f,
            scale = 1.08f,
            opacity = 0.90f,
            isPrimaryAction = true
        ),
        VirtualControlNode(
            id = "btn_place",
            binding = GlfwKeyBinding.MOUSE_PLACE,
            normalizedX = 0.89f,
            normalizedY = 0.42f,
            scale = 1.05f,
            opacity = 0.88f
        ),
        VirtualControlNode(
            id = "btn_inv",
            binding = GlfwKeyBinding.KEY_INVENTORY,
            normalizedX = 0.66f,
            normalizedY = 0.82f,
            scale = 0.94f,
            opacity = 0.84f
        ),
        VirtualControlNode(
            id = "btn_esc",
            binding = GlfwKeyBinding.KEY_PAUSE_ESC,
            normalizedX = 0.30f,
            normalizedY = 0.09f,
            scale = 0.84f,
            opacity = 0.82f
        ),
        VirtualControlNode(
            id = "btn_tab",
            binding = GlfwKeyBinding.KEY_PLAYER_LIST_TAB,
            normalizedX = 0.38f,
            normalizedY = 0.09f,
            scale = 0.84f,
            opacity = 0.78f
        ),
        VirtualControlNode(
            id = "btn_chat",
            binding = GlfwKeyBinding.KEY_CHAT,
            normalizedX = 0.46f,
            normalizedY = 0.09f,
            scale = 0.84f,
            opacity = 0.78f
        ),
        VirtualControlNode(
            id = "btn_f3",
            binding = GlfwKeyBinding.KEY_DEBUG_F3,
            normalizedX = 0.54f,
            normalizedY = 0.09f,
            scale = 0.84f,
            opacity = 0.78f,
            isStickyToggle = true
        ),
        VirtualControlNode(
            id = "btn_f5",
            binding = GlfwKeyBinding.KEY_THIRD_PERSON_F5,
            normalizedX = 0.62f,
            normalizedY = 0.09f,
            scale = 0.84f,
            opacity = 0.78f
        )
    )

    fun loadLayout(filesDir: File): List<VirtualControlNode> {
        val file = File(filesDir, "controller_layout_v2.json")
        if (!file.exists()) return defaultLandscapeLayout()
        return runCatching {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<VirtualControlNode>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    VirtualControlNode(
                        id = o.optString("id"),
                        binding = GlfwKeyBinding.valueOf(o.optString("binding")),
                        normalizedX = o.optDouble("normalizedX", 0.5).toFloat(),
                        normalizedY = o.optDouble("normalizedY", 0.5).toFloat(),
                        scale = o.optDouble("scale", 1.0).toFloat(),
                        opacity = o.optDouble("opacity", 0.85).toFloat(),
                        isStickyToggle = o.optBoolean("isStickyToggle", false),
                        isPrimaryAction = o.optBoolean("isPrimaryAction", false)
                    )
                )
            }
            list.ifEmpty { defaultLandscapeLayout() }
        }.getOrElse { defaultLandscapeLayout() }
    }

    fun saveLayout(filesDir: File, nodes: List<VirtualControlNode>) {
        runCatching {
            val arr = JSONArray()
            for (node in nodes) {
                arr.put(
                    JSONObject().apply {
                        put("id", node.id)
                        put("binding", node.binding.name)
                        put("normalizedX", node.normalizedX.toDouble())
                        put("normalizedY", node.normalizedY.toDouble())
                        put("scale", node.scale.toDouble())
                        put("opacity", node.opacity.toDouble())
                        put("isStickyToggle", node.isStickyToggle)
                        put("isPrimaryAction", node.isPrimaryAction)
                    }
                )
            }
            File(filesDir, "controller_layout_v2.json").writeText(arr.toString(2))
        }
    }
}
