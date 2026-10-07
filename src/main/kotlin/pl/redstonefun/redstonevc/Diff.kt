package pl.redstonefun.redstonevc

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.world.World as WorldEditWorld
import org.bukkit.Location
import org.bukkit.World

data class BlockPos(val x: Int, val y: Int, val z: Int) {

    operator fun plus(other: BlockPos): BlockPos = BlockPos(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: BlockPos): BlockPos = BlockPos(x - other.x, y - other.y, z - other.z)

    fun toLocation(world: World): Location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
}

data class BlockBox(val min: BlockPos, val max: BlockPos) {

    val sizeX: Int get() = max.x - min.x + 1
    val sizeY: Int get() = max.y - min.y + 1
    val sizeZ: Int get() = max.z - min.z + 1

    fun contains(pos: BlockPos): Boolean =
        pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z

    companion object {
        fun of(a: BlockPos, b: BlockPos): BlockBox = BlockBox(
            BlockPos(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
            BlockPos(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z)),
        )
    }
}

enum class ChangeType { ADDED, REMOVED, MODIFIED }

data class BlockChange(val pos: BlockPos, val from: String, val to: String, val type: ChangeType)

interface BlockSource {
    fun stateAt(rel: BlockPos): String
}

class ClipboardBlockSource(private val clipboard: Clipboard) : BlockSource {
    private val origin: BlockVector3 = clipboard.region.minimumPoint
    override fun stateAt(rel: BlockPos): String = clipboard.getBlock(origin.add(rel.x, rel.y, rel.z)).asString
}

class WorldBlockSource(private val world: WorldEditWorld, private val origin: BlockVector3) : BlockSource {
    override fun stateAt(rel: BlockPos): String = world.getBlock(origin.add(rel.x, rel.y, rel.z)).asString
}

object DiffEngine {

    private val AIR = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

    fun diff(box: BlockBox, old: BlockSource, new: BlockSource): List<BlockChange> {
        val changes = ArrayList<BlockChange>()
        for (x in 0 until box.sizeX) {
            for (y in 0 until box.sizeY) {
                for (z in 0 until box.sizeZ) {
                    val rel = BlockPos(x, y, z)
                    val before = old.stateAt(rel)
                    val after = new.stateAt(rel)
                    if (before == after) continue
                    val type = when {
                        before in AIR && after !in AIR -> ChangeType.ADDED
                        before !in AIR && after in AIR -> ChangeType.REMOVED
                        else -> ChangeType.MODIFIED
                    }
                    changes += BlockChange(rel, before, after, type)
                }
            }
        }
        return changes
    }
}
