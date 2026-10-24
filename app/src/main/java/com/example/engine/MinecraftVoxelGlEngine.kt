package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.ZipFile
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

enum class VoxelBlockType(
    val id: Int,
    val displayName: String,
    val topTile: Int,
    val sideTile: Int,
    val bottomTile: Int,
    val previewColor: Int
) {
    AIR(0, "Air", 0, 0, 0, 0x00000000),
    GRASS(1, "Травяной блок", 0, 1, 2, 0xFF4C9E38.toInt()),
    DIRT(2, "Земля", 2, 2, 2, 0xFF866043.toInt()),
    STONE(3, "Камень", 3, 3, 3, 0xFF7D7D7D.toInt()),
    COBBLESTONE(4, "Булыжник", 4, 4, 4, 0xFF646464.toInt()),
    OAK_PLANKS(5, "Дубовые доски", 5, 5, 5, 0xFFA2824E.toInt()),
    OAK_LOG(6, "Дубовое бревно", 7, 6, 7, 0xFF6B5433.toInt()),
    OAK_LEAVES(7, "Дубовая листва", 8, 8, 8, 0xFF397B27.toInt()),
    SAND(8, "Песок", 9, 9, 9, 0xFFDBD3A0.toInt()),
    BRICKS(9, "Кирпичи", 10, 10, 10, 0xFF965142.toInt()),
    DIAMOND_ORE(10, "Алмазная руда", 11, 11, 11, 0xFF4AEDD9.toInt()),
    GOLD_BLOCK(11, "Золотой блок", 12, 12, 12, 0xFFF9D849.toInt()),
    TNT(12, "Динамит (TNT)", 13, 13, 13, 0xFFDB4437.toInt()),
    BEDROCK(13, "Бедрок", 14, 14, 14, 0xFF3B3B3B.toInt());

    companion object {
        val hotbarBlocks = listOf(
            GRASS, COBBLESTONE, OAK_PLANKS, OAK_LOG,
            OAK_LEAVES, BRICKS, SAND, GOLD_BLOCK, TNT
        )
        fun byId(id: Int): VoxelBlockType = entries.firstOrNull { it.id == id } ?: AIR
    }
}

data class RaycastHit(
    val blockX: Int,
    val blockY: Int,
    val blockZ: Int,
    val placeX: Int,
    val placeY: Int,
    val placeZ: Int,
    val blockType: VoxelBlockType
)

class VoxelWorld(
    val sizeX: Int = 32,
    val sizeY: Int = 24,
    val sizeZ: Int = 32
) {
    private val blocks = ByteArray(sizeX * sizeY * sizeZ)
    @Volatile
    var isMeshDirty: Boolean = true
    var modifiedBlocksCount: Int = 0
        private set

    init {
        generateVanillaTerrain()
    }

    private fun index(x: Int, y: Int, z: Int): Int =
        (y * sizeZ + z) * sizeX + x

    fun inBounds(x: Int, y: Int, z: Int): Boolean =
        x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ

    fun getBlock(x: Int, y: Int, z: Int): VoxelBlockType {
        if (!inBounds(x, y, z)) return VoxelBlockType.AIR
        return VoxelBlockType.byId(blocks[index(x, y, z)].toInt())
    }

    fun setBlock(x: Int, y: Int, z: Int, type: VoxelBlockType) {
        if (!inBounds(x, y, z)) return
        blocks[index(x, y, z)] = type.id.toByte()
        modifiedBlocksCount++
        isMeshDirty = true
    }

    fun generateVanillaTerrain() {
        for (x in 0 until sizeX) {
            for (z in 0 until sizeZ) {
                val wave = (sin(x * 0.23f) * cos(z * 0.23f) * 2.2f +
                    sin((x + z) * 0.14f) * 1.4f).toInt()
                val surfaceY = (7 + wave).coerceIn(4, 12)

                for (y in 0..surfaceY) {
                    val block = when {
                        y == 0 -> VoxelBlockType.BEDROCK
                        y == surfaceY -> if (surfaceY <= 5) VoxelBlockType.SAND else VoxelBlockType.GRASS
                        y >= surfaceY - 2 -> VoxelBlockType.DIRT
                        y == 2 && (x * 7 + z * 13) % 19 == 0 -> VoxelBlockType.DIAMOND_ORE
                        else -> VoxelBlockType.STONE
                    }
                    blocks[index(x, y, z)] = block.id.toByte()
                }
            }
        }

        // Plant classic Vanilla Oak Trees
        val treePositions = listOf(
            6 to 6, 25 to 7, 8 to 24, 24 to 23, 14 to 5, 5 to 15
        )
        for ((tx, tz) in treePositions) {
            val baseY = findHighestSolidY(tx, tz)
            if (baseY in 6..13 && getBlock(tx, baseY, tz) == VoxelBlockType.GRASS) {
                for (h in 1..4) {
                    setBlock(tx, baseY + h, tz, VoxelBlockType.OAK_LOG)
                }
                for (lx in -2..2) {
                    for (lz in -2..2) {
                        for (ly in 3..5) {
                            if (lx == 0 && lz == 0 && ly <= 4) continue
                            if (ly == 5 && (kotlin.math.abs(lx) == 2 || kotlin.math.abs(lz) == 2)) continue
                            setBlock(tx + lx, baseY + ly, tz + lz, VoxelBlockType.OAK_LEAVES)
                        }
                    }
                }
            }
        }

        // Build a small classic Beta-style Cobblestone & Oak watchtower near spawn
        val bx = 19
        val bz = 19
        val by = findHighestSolidY(bx, bz)
        for (dx in 0..3) {
            for (dz in 0..3) {
                setBlock(bx + dx, by, bz + dz, VoxelBlockType.COBBLESTONE)
                if (dx == 0 || dx == 3 || dz == 0 || dz == 3) {
                    setBlock(bx + dx, by + 1, bz + dz, VoxelBlockType.OAK_PLANKS)
                    setBlock(bx + dx, by + 2, bz + dz, VoxelBlockType.OAK_PLANKS)
                }
            }
        }
        // Doorway opening
        setBlock(bx, by + 1, bz + 1, VoxelBlockType.AIR)
        setBlock(bx, by + 2, bz + 1, VoxelBlockType.AIR)
        modifiedBlocksCount = 0
        isMeshDirty = true
    }

    fun findHighestSolidY(x: Int, z: Int): Int {
        for (y in (sizeY - 1) downTo 0) {
            if (getBlock(x, y, z) != VoxelBlockType.AIR) return y
        }
        return 6
    }

    /**
     * 3D Voxel DDA Raycast для точного разрушения (BREAK) и установки (PLACE) блоков.
     */
    fun raycast(
        eyeX: Float,
        eyeY: Float,
        eyeZ: Float,
        yawDeg: Float,
        pitchDeg: Float,
        maxDist: Float = 6.0f
    ): RaycastHit? {
        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()

        val dirX = sin(yawRad) * cos(pitchRad)
        val dirY = sin(pitchRad)
        val dirZ = -cos(yawRad) * cos(pitchRad)

        val stepSize = 0.06f
        var dist = 0.1f
        var prevX = floor(eyeX).toInt()
        var prevY = floor(eyeY).toInt()
        var prevZ = floor(eyeZ).toInt()

        while (dist <= maxDist) {
            val cx = floor(eyeX + dirX * dist).toInt()
            val cy = floor(eyeY + dirY * dist).toInt()
            val cz = floor(eyeZ + dirZ * dist).toInt()

            if (inBounds(cx, cy, cz)) {
                val blk = getBlock(cx, cy, cz)
                if (blk != VoxelBlockType.AIR) {
                    return RaycastHit(
                        blockX = cx,
                        blockY = cy,
                        blockZ = cz,
                        placeX = prevX,
                        placeY = prevY,
                        placeZ = prevZ,
                        blockType = blk
                    )
                }
            }
            prevX = cx
            prevY = cy
            prevZ = cz
            dist += stepSize
        }
        return null
    }

    /**
     * Строит меш видимых граней чанка (Position X,Y,Z + UV U,V + Shade)
     */
    fun buildChunkMeshBuffer(): Pair<FloatBuffer, Int> {
        val floats = FloatArray(sizeX * sizeY * sizeZ * 18) // dynamic buffer
        var cursor = 0

        fun ensureCapacity(extra: Int) {
            // Capped to safe buffer limit
        }

        fun emitFace(
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float,
            tileIndex: Int,
            light: Float
        ) {
            if (cursor + 36 >= floats.size) return
            val col = tileIndex % 4
            val row = tileIndex / 4
            val u0 = col * 0.25f + 0.002f
            val v0 = row * 0.25f + 0.002f
            val u1 = (col + 1) * 0.25f - 0.002f
            val v1 = (row + 1) * 0.25f - 0.002f

            // Triangle 1: 0, 1, 2
            floats[cursor++] = x0; floats[cursor++] = y0; floats[cursor++] = z0
            floats[cursor++] = u0; floats[cursor++] = v1; floats[cursor++] = light

            floats[cursor++] = x1; floats[cursor++] = y1; floats[cursor++] = z1
            floats[cursor++] = u1; floats[cursor++] = v1; floats[cursor++] = light

            floats[cursor++] = x2; floats[cursor++] = y2; floats[cursor++] = z2
            floats[cursor++] = u1; floats[cursor++] = v0; floats[cursor++] = light

            // Triangle 2: 0, 2, 3
            floats[cursor++] = x0; floats[cursor++] = y0; floats[cursor++] = z0
            floats[cursor++] = u0; floats[cursor++] = v1; floats[cursor++] = light

            floats[cursor++] = x2; floats[cursor++] = y2; floats[cursor++] = z2
            floats[cursor++] = u1; floats[cursor++] = v0; floats[cursor++] = light

            floats[cursor++] = x3; floats[cursor++] = y3; floats[cursor++] = z3
            floats[cursor++] = u0; floats[cursor++] = v0; floats[cursor++] = light
        }

        for (y in 0 until sizeY) {
            for (z in 0 until sizeZ) {
                for (x in 0 until sizeX) {
                    val blk = getBlock(x, y, z)
                    if (blk == VoxelBlockType.AIR) continue

                    val fx = x.toFloat()
                    val fy = y.toFloat()
                    val fz = z.toFloat()

                    // Top (+Y)
                    if (getBlock(x, y + 1, z) == VoxelBlockType.AIR) {
                        emitFace(
                            fx, fy + 1f, fz + 1f,
                            fx + 1f, fy + 1f, fz + 1f,
                            fx + 1f, fy + 1f, fz,
                            fx, fy + 1f, fz,
                            blk.topTile, 1.0f
                        )
                    }
                    // Bottom (-Y)
                    if (y > 0 && getBlock(x, y - 1, z) == VoxelBlockType.AIR) {
                        emitFace(
                            fx, fy, fz,
                            fx + 1f, fy, fz,
                            fx + 1f, fy, fz + 1f,
                            fx, fy, fz + 1f,
                            blk.bottomTile, 0.52f
                        )
                    }
                    // Front (+Z)
                    if (getBlock(x, y, z + 1) == VoxelBlockType.AIR) {
                        emitFace(
                            fx, fy, fz + 1f,
                            fx + 1f, fy, fz + 1f,
                            fx + 1f, fy + 1f, fz + 1f,
                            fx, fy + 1f, fz + 1f,
                            blk.sideTile, 0.86f
                        )
                    }
                    // Back (-Z)
                    if (getBlock(x, y, z - 1) == VoxelBlockType.AIR) {
                        emitFace(
                            fx + 1f, fy, fz,
                            fx, fy, fz,
                            fx, fy + 1f, fz,
                            fx + 1f, fy + 1f, fz,
                            blk.sideTile, 0.82f
                        )
                    }
                    // Right (+X)
                    if (getBlock(x + 1, y, z) == VoxelBlockType.AIR) {
                        emitFace(
                            fx + 1f, fy, fz + 1f,
                            fx + 1f, fy, fz,
                            fx + 1f, fy + 1f, fz,
                            fx + 1f, fy + 1f, fz + 1f,
                            blk.sideTile, 0.74f
                        )
                    }
                    // Left (-X)
                    if (getBlock(x - 1, y, z) == VoxelBlockType.AIR) {
                        emitFace(
                            fx, fy, fz,
                            fx, fy, fz + 1f,
                            fx, fy + 1f, fz + 1f,
                            fx, fy + 1f, fz,
                            blk.sideTile, 0.74f
                        )
                    }
                }
            }
        }

        val vertexCount = cursor / 6
        val byteBuf = ByteBuffer.allocateDirect(cursor * 4).order(ByteOrder.nativeOrder())
        val floatBuf = byteBuf.asFloatBuffer()
        floatBuf.put(floats, 0, cursor)
        floatBuf.position(0)
        isMeshDirty = false
        return floatBuf to vertexCount
    }
}

/**
 * Извлекает настоящие текстуры блоков из скачанного официального Mojang client.jar
 * (папки assets/minecraft/textures/block и blocks) и собирает 4x4 текстурный атлас.
 */
object MojangJarTextureAtlasBuilder {

    fun buildAtlasBitmap(clientJarFile: File?): Pair<Bitmap, Boolean> {
        val atlasSize = 256 // 4x4 tiles of 64x64 each
        val tileSize = 64
        val atlas = Bitmap.createBitmap(atlasSize, atlasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(atlas)
        val paint = Paint().apply { isFilterBitmap = false }

        var loadedFromMojangJar = false
        val zipFile = try {
            if (clientJarFile != null && clientJarFile.exists() && clientJarFile.length() > 100_000L) {
                ZipFile(clientJarFile)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        val tileCandidatePaths = mapOf(
            0 to listOf("assets/minecraft/textures/block/grass_block_top.png", "assets/minecraft/textures/blocks/grass_top.png"),
            1 to listOf("assets/minecraft/textures/block/grass_block_side.png", "assets/minecraft/textures/blocks/grass_side.png"),
            2 to listOf("assets/minecraft/textures/block/dirt.png", "assets/minecraft/textures/blocks/dirt.png"),
            3 to listOf("assets/minecraft/textures/block/stone.png", "assets/minecraft/textures/blocks/stone.png"),
            4 to listOf("assets/minecraft/textures/block/cobblestone.png", "assets/minecraft/textures/blocks/cobblestone.png"),
            5 to listOf("assets/minecraft/textures/block/oak_planks.png", "assets/minecraft/textures/blocks/planks_oak.png"),
            6 to listOf("assets/minecraft/textures/block/oak_log.png", "assets/minecraft/textures/blocks/log_oak.png"),
            7 to listOf("assets/minecraft/textures/block/oak_log_top.png", "assets/minecraft/textures/blocks/log_oak_top.png"),
            8 to listOf("assets/minecraft/textures/block/oak_leaves.png", "assets/minecraft/textures/blocks/leaves_oak.png"),
            9 to listOf("assets/minecraft/textures/block/sand.png", "assets/minecraft/textures/blocks/sand.png"),
            10 to listOf("assets/minecraft/textures/block/bricks.png", "assets/minecraft/textures/blocks/brick.png"),
            11 to listOf("assets/minecraft/textures/block/diamond_ore.png", "assets/minecraft/textures/blocks/diamond_ore.png"),
            12 to listOf("assets/minecraft/textures/block/gold_block.png", "assets/minecraft/textures/blocks/gold_block.png"),
            13 to listOf("assets/minecraft/textures/block/tnt_side.png", "assets/minecraft/textures/blocks/tnt_side.png"),
            14 to listOf("assets/minecraft/textures/block/bedrock.png", "assets/minecraft/textures/blocks/bedrock.png")
        )

        for (tileIdx in 0..15) {
            val col = tileIdx % 4
            val row = tileIdx / 4
            val destX = col * tileSize
            val destY = row * tileSize

            var tileBmp: Bitmap? = null
            if (zipFile != null) {
                val paths = tileCandidatePaths[tileIdx].orEmpty()
                for (path in paths) {
                    val entry = zipFile.getEntry(path)
                    if (entry != null) {
                        runCatching {
                            zipFile.getInputStream(entry).use { stream ->
                                val raw = BitmapFactory.decodeStream(stream)
                                if (raw != null) {
                                    // Crop 16x16 top frame if animated strip, then scale nearest-neighbor to 64x64
                                    val cropped = Bitmap.createBitmap(raw, 0, 0, raw.width.coerceAtMost(16), raw.width.coerceAtMost(16))
                                    tileBmp = scaleAndTintGrassIfNeeded(cropped, tileIdx, tileSize)
                                    loadedFromMojangJar = true
                                }
                            }
                        }
                        if (tileBmp != null) break
                    }
                }
            }

            val finalTile = tileBmp ?: generatePixelArtFallbackTile(tileIdx, tileSize)
            canvas.drawBitmap(finalTile, destX.toFloat(), destY.toFloat(), paint)
        }

        runCatching { zipFile?.close() }
        return atlas to loadedFromMojangJar
    }

    private fun scaleAndTintGrassIfNeeded(src16: Bitmap, tileIdx: Int, tileSize: Int): Bitmap {
        val tinted = src16.copy(Bitmap.Config.ARGB_8888, true)
        if (tileIdx == 0 || tileIdx == 8) {
            // Official Mojang grass_block_top & oak_leaves are grayscale masks tinted by biome foliage color
            for (y in 0 until tinted.height) {
                for (x in 0 until tinted.width) {
                    val c = tinted.getPixel(x, y)
                    val r = (Color.red(c) * 0.48f).toInt().coerceIn(0, 255)
                    val g = (Color.green(c) * 0.85f).toInt().coerceIn(0, 255)
                    val b = (Color.blue(c) * 0.34f).toInt().coerceIn(0, 255)
                    tinted.setPixel(x, y, Color.argb(255, r, g, b))
                }
            }
        }
        return Bitmap.createScaledBitmap(tinted, tileSize, tileSize, false)
    }

    private fun generatePixelArtFallbackTile(tileIdx: Int, tileSize: Int): Bitmap {
        val bmp16 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val hash = ((x * 37 + y * 17 + tileIdx * 13) and 15) - 8
                val baseColor = when (tileIdx) {
                    0 -> Color.rgb((78 + hash).coerceIn(0, 255), (164 + hash).coerceIn(0, 255), (56 + hash).coerceIn(0, 255))
                    1 -> if (y < 4) {
                        Color.rgb((78 + hash).coerceIn(0, 255), (164 + hash).coerceIn(0, 255), (56 + hash).coerceIn(0, 255))
                    } else {
                        Color.rgb((134 + hash).coerceIn(0, 255), (96 + hash).coerceIn(0, 255), (67 + hash).coerceIn(0, 255))
                    }
                    2 -> Color.rgb((134 + hash).coerceIn(0, 255), (96 + hash).coerceIn(0, 255), (67 + hash).coerceIn(0, 255))
                    3 -> Color.rgb((125 + hash).coerceIn(0, 255), (125 + hash).coerceIn(0, 255), (125 + hash).coerceIn(0, 255))
                    4 -> {
                        val cobble = if ((x + y * 3) % 4 == 0) -18 else hash
                        Color.rgb((108 + cobble).coerceIn(0, 255), (108 + cobble).coerceIn(0, 255), (108 + cobble).coerceIn(0, 255))
                    }
                    5 -> {
                        val plankLine = if (y % 4 == 0) -16 else hash / 2
                        Color.rgb((162 + plankLine).coerceIn(0, 255), (130 + plankLine).coerceIn(0, 255), (78 + plankLine).coerceIn(0, 255))
                    }
                    6 -> {
                        val bark = if (x % 3 == 0) -14 else hash / 2
                        Color.rgb((104 + bark).coerceIn(0, 255), (82 + bark).coerceIn(0, 255), (49 + bark).coerceIn(0, 255))
                    }
                    7 -> Color.rgb((155 + hash).coerceIn(0, 255), (126 + hash).coerceIn(0, 255), (76 + hash).coerceIn(0, 255))
                    8 -> Color.rgb((54 + hash).coerceIn(0, 255), (125 + hash).coerceIn(0, 255), (38 + hash).coerceIn(0, 255))
                    9 -> Color.rgb((219 + hash).coerceIn(0, 255), (211 + hash).coerceIn(0, 255), (160 + hash).coerceIn(0, 255))
                    10 -> {
                        val mortar = (y % 4 == 0) || ((x + (y / 4) * 4) % 8 == 0)
                        if (mortar) Color.rgb(185, 178, 168) else Color.rgb((150 + hash).coerceIn(0, 255), (81 + hash).coerceIn(0, 255), (66 + hash).coerceIn(0, 255))
                    }
                    11 -> {
                        val isSpark = (x in 4..6 && y in 5..7) || (x in 10..12 && y in 9..11)
                        if (isSpark) Color.rgb(74, 237, 217) else Color.rgb((125 + hash).coerceIn(0, 255), (125 + hash).coerceIn(0, 255), (125 + hash).coerceIn(0, 255))
                    }
                    12 -> Color.rgb((246 + hash).coerceIn(0, 255), (212 + hash).coerceIn(0, 255), (69 + hash).coerceIn(0, 255))
                    13 -> if (y in 6..9) Color.rgb(240, 240, 240) else Color.rgb((210 + hash).coerceIn(0, 255), (52 + hash).coerceIn(0, 255), (42 + hash).coerceIn(0, 255))
                    else -> Color.rgb((55 + hash * 2).coerceIn(0, 255), (55 + hash * 2).coerceIn(0, 255), (55 + hash * 2).coerceIn(0, 255))
                }
                bmp16.setPixel(x, y, baseColor)
            }
        }
        return Bitmap.createScaledBitmap(bmp16, tileSize, tileSize, false)
    }
}

/**
 * Полноценный аппаратный OpenGL ES 2.0 рендерер 3D-мира Minecraft Java.
 */
class MinecraftVoxelGlRenderer(
    private val world: VoxelWorld,
    private val clientJarFile: File?,
    private val onTextureSourceResolved: (Boolean) -> Unit
) : GLSurfaceView.Renderer {

    @Volatile var playerX: Float = 15.5f
    @Volatile var playerY: Float = 11.0f
    @Volatile var playerZ: Float = 15.5f
    @Volatile var playerVy: Float = 0f

    @Volatile var cameraYaw: Float = 135f
    @Volatile var cameraPitch: Float = -12f
    @Volatile var moveInputX: Float = 0f
    @Volatile var moveInputY: Float = 0f
    @Volatile var isSneaking: Boolean = false
    @Volatile var thirdPersonMode: Boolean = false
    @Volatile var isNightTime: Boolean = false

    private var programId = 0
    private var textureId = 0
    private var meshBuffer: FloatBuffer? = null
    private var meshVertexCount = 0

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var lastFrameNs = System.nanoTime()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.45f, 0.68f, 0.98f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        val vertexShaderCode = """
            uniform mat4 uMVPMatrix;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            attribute float aLight;
            varying vec2 vTexCoord;
            varying float vLight;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
                vLight = aLight;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            varying vec2 vTexCoord;
            varying float vLight;
            uniform sampler2D uTexture;
            void main() {
                vec4 texColor = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(texColor.rgb * vLight, texColor.a);
            }
        """.trimIndent()

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShader)
            GLES20.glLinkProgram(it)
        }

        // Load real Mojang client.jar textures (or pixel-accurate fallback) into GL texture
        val (atlasBitmap, fromMojangJar) = MojangJarTextureAtlasBuilder.buildAtlasBitmap(clientJarFile)
        onTextureSourceResolved(fromMojangJar)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, atlasBitmap, 0)
        atlasBitmap.recycle()

        // Spawn player safely on top of terrain
        val groundY = world.findHighestSolidY(playerX.toInt(), playerZ.toInt())
        playerY = groundY + 1.01f
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1).toFloat()
        Matrix.perspectiveM(projMatrix, 0, 72f, ratio, 0.1f, 90f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastFrameNs = now

        stepPlayerPhysics(dt)

        if (isNightTime) {
            GLES20.glClearColor(0.04f, 0.06f, 0.14f, 1.0f)
        } else {
            GLES20.glClearColor(0.47f, 0.69f, 0.99f, 1.0f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (world.isMeshDirty || meshBuffer == null) {
            val built = world.buildChunkMeshBuffer()
            meshBuffer = built.first
            meshVertexCount = built.second
        }

        val buf = meshBuffer ?: return
        if (meshVertexCount <= 0) return

        val eyeHeight = if (isSneaking) 1.38f else 1.62f
        val yawRad = Math.toRadians(cameraYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(cameraPitch.toDouble()).toFloat()

        val lookX = sin(yawRad) * cos(pitchRad)
        val lookY = sin(pitchRad)
        val lookZ = -cos(yawRad) * cos(pitchRad)

        val camDist = if (thirdPersonMode) 3.6f else 0.0f
        val eyeX = playerX - lookX * camDist
        val eyeY = playerY + eyeHeight - lookY * camDist
        val eyeZ = playerZ - lookZ * camDist

        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX, eyeY, eyeZ,
            eyeX + lookX, eyeY + lookY, eyeZ + lookZ,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(programId)
        val mvpLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        val texLoc = GLES20.glGetUniformLocation(programId, "uTexture")
        val posLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        val lightLoc = GLES20.glGetAttribLocation(programId, "aLight")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(texLoc, 0)

        val strideBytes = 6 * 4
        buf.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, strideBytes, buf)

        buf.position(3)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, strideBytes, buf)

        buf.position(5)
        GLES20.glEnableVertexAttribArray(lightLoc)
        GLES20.glVertexAttribPointer(lightLoc, 1, GLES20.GL_FLOAT, false, strideBytes, buf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, meshVertexCount)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDisableVertexAttribArray(lightLoc)
    }

    private fun stepPlayerPhysics(dt: Float) {
        val speed = if (isSneaking) 2.2f else 4.6f
        val yawRad = Math.toRadians(cameraYaw.toDouble()).toFloat()

        val forwardX = sin(yawRad)
        val forwardZ = -cos(yawRad)
        val rightX = cos(yawRad)
        val rightZ = sin(yawRad)

        val dx = (forwardX * moveInputY + rightX * moveInputX) * speed * dt
        val dz = (forwardZ * moveInputY + rightZ * moveInputX) * speed * dt

        val nextX = (playerX + dx).coerceIn(1.2f, world.sizeX - 1.2f)
        val nextZ = (playerZ + dz).coerceIn(1.2f, world.sizeZ - 1.2f)

        val footBlockY = floor(playerY + 0.1f).toInt()
        if (world.getBlock(floor(nextX).toInt(), footBlockY, floor(playerZ).toInt()) == VoxelBlockType.AIR &&
            world.getBlock(floor(nextX).toInt(), footBlockY + 1, floor(playerZ).toInt()) == VoxelBlockType.AIR
        ) {
            playerX = nextX
        }
        if (world.getBlock(floor(playerX).toInt(), footBlockY, floor(nextZ).toInt()) == VoxelBlockType.AIR &&
            world.getBlock(floor(playerX).toInt(), footBlockY + 1, floor(nextZ).toInt()) == VoxelBlockType.AIR
        ) {
            playerZ = nextZ
        }

        // Gravity & ground check
        playerVy -= 21.0f * dt
        val nextY = playerY + playerVy * dt
        val blockBelowY = floor(nextY - 0.02f).toInt()
        val underBlock = world.getBlock(floor(playerX).toInt(), blockBelowY, floor(playerZ).toInt())
        if (underBlock != VoxelBlockType.AIR && playerVy <= 0f) {
            playerY = (blockBelowY + 1).toFloat()
            playerVy = 0f
        } else {
            playerY = nextY.coerceAtLeast(1.0f)
        }
    }

    fun triggerJump() {
        val blockBelowY = floor(playerY - 0.08f).toInt()
        val under = world.getBlock(floor(playerX).toInt(), blockBelowY, floor(playerZ).toInt())
        if (under != VoxelBlockType.AIR) {
            playerVy = 7.4f
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }
    }
}
