package pl.redstonefun.redstonevc

import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.session.ClipboardHolder
import org.bukkit.World
import org.bukkit.entity.Player
import java.io.InputStream
import java.io.OutputStream

object WorldEditBridge {

    fun selectionBounds(player: Player): BlockBox? {
        val session = WorldEdit.getInstance().sessionManager.get(BukkitAdapter.adapt(player))
        val region = try {
            session.getSelection(BukkitAdapter.adapt(player.world))
        } catch (_: IncompleteRegionException) {
            return null
        }
        val min = region.minimumPoint
        val max = region.maximumPoint
        return BlockBox.of(
            BlockPos(min.x(), min.y(), min.z()),
            BlockPos(max.x(), max.y(), max.z()),
        )
    }

    fun capture(world: World, box: BlockBox): Clipboard {
        val weWorld = BukkitAdapter.adapt(world)
        val min = box.min.toVector()
        val max = box.max.toVector()
        val region = CuboidRegion(weWorld, min, max)
        val clipboard = BlockArrayClipboard(region)
        clipboard.origin = min
        val copy = ForwardExtentCopy(weWorld, region, clipboard, min)
        copy.setCopyingEntities(false)
        copy.setCopyingBiomes(false)
        Operations.complete(copy)
        return clipboard
    }

    fun write(clipboard: Clipboard, out: OutputStream) {
        BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out).use { it.write(clipboard) }
    }

    fun read(input: InputStream): Clipboard =
        BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getReader(input).use { it.read() }

    fun emptyClipboard(box: BlockBox): Clipboard {
        val min = box.min.toVector()
        val clipboard = BlockArrayClipboard(CuboidRegion(min, box.max.toVector()))
        clipboard.origin = min
        return clipboard
    }

    fun copyTo(source: Clipboard, sourceBox: BlockBox, destination: Clipboard, destinationBox: BlockBox) {
        val region = CuboidRegion(sourceBox.min.toVector(), sourceBox.max.toVector())
        val copy = ForwardExtentCopy(source, region, destination, destinationBox.min.toVector())
        copy.setCopyingEntities(false)
        copy.setCopyingBiomes(false)
        Operations.complete(copy)
    }

    fun paste(world: World, box: BlockBox, clipboard: Clipboard) {
        val operation = ClipboardHolder(clipboard)
            .createPaste(BukkitAdapter.adapt(world))
            .to(box.min.toVector())
            .ignoreAirBlocks(false)
            .copyEntities(false)
            .copyBiomes(false)
            .build()
        Operations.complete(operation)
    }

    fun blockState(world: World, pos: BlockPos): String =
        BukkitAdapter.adapt(world).getBlock(pos.toVector()).asString

    fun worldSource(world: World, box: BlockBox): BlockSource =
        WorldBlockSource(BukkitAdapter.adapt(world), box.min.toVector())

    private fun BlockPos.toVector(): BlockVector3 = BlockVector3.at(x, y, z)
}
