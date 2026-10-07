package pl.redstonefun.redstonevc

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f

enum class HighlightColor(val material: Material, val glow: Color) {
    GREEN(Material.LIME_STAINED_GLASS, Color.fromRGB(0x39FF14)),
    RED(Material.RED_STAINED_GLASS, Color.fromRGB(0xFF2D2D)),
    YELLOW(Material.YELLOW_STAINED_GLASS, Color.fromRGB(0xFFE23D)),
    WHITE(Material.WHITE_STAINED_GLASS, Color.WHITE),
}

class Highlight internal constructor(private val entities: List<Entity>) {
    internal fun remove() {
        entities.forEach { if (it.isValid) it.remove() }
    }
}

class HighlightManager(private val plugin: Plugin) {

    private val active = HashSet<Highlight>()

    fun show(
        world: World,
        positions: Collection<BlockPos>,
        color: HighlightColor,
        durationTicks: Long,
        scale: Float = 1f,
    ): Highlight {
        val blockData = color.material.createBlockData()
        val highlight = Highlight(positions.map { spawn(world, it, color, blockData, scale) })
        active += highlight
        if (durationTicks > 0L) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable { clear(highlight) }, durationTicks)
        }
        return highlight
    }

    fun clear(highlight: Highlight) {
        if (active.remove(highlight)) highlight.remove()
    }

    fun clearAll() {
        active.toList().forEach(::clear)
    }

    private fun spawn(world: World, pos: BlockPos, color: HighlightColor, blockData: org.bukkit.block.data.BlockData, scale: Float): BlockDisplay {
        val display = world.spawn(pos.toLocation(world), BlockDisplay::class.java)
        display.block = blockData
        if (scale != 1f) {
            val offset = (1f - scale) / 2f
            display.transformation = Transformation(
                Vector3f(offset, offset, offset),
                AxisAngle4f(),
                Vector3f(scale, scale, scale),
                AxisAngle4f(),
            )
        }
        display.glowColorOverride = color.glow
        display.isGlowing = true
        display.brightness = Display.Brightness(15, 15)
        display.isPersistent = false
        display.isInvulnerable = true
        display.isSilent = true
        display.setGravity(false)
        return display
    }

    companion object {
        const val CELL_SCALE = 1.01f
    }
}
