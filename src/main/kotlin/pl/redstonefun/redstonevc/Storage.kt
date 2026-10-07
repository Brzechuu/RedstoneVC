package pl.redstonefun.redstonevc

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.configuration.file.YamlConfiguration
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class Commit(
    val id: String,
    val parent: String?,
    val snapshot: String,
    val message: String,
    val author: String,
    val timestamp: Long,
)

class Repository(
    val directory: File,
    val world: String,
    val owner: UUID,
    val ownerName: String,
    val box: BlockBox,
    var branch: String = "master",
    var head: String? = null,
    val branches: MutableMap<String, String> = LinkedHashMap(),
) {
    val name: String get() = directory.name
    val commits: File get() = File(directory, "commits")
    val objects: File get() = File(directory, "objects")

    fun pointBranch(id: String?) {
        head = id
        if (id == null) branches.remove(branch) else branches[branch] = id
    }
}

private const val META_FILE = "repo.yml"

class RepoManager(private val dataFolder: File) {

    private val active = HashMap<UUID, String>()

    fun list(owner: UUID): List<String> {
        val dir = ownerDir(owner)
        return dir.listFiles { file -> file.isDirectory && File(file, META_FILE).isFile }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    fun load(owner: UUID, name: String): Repository? {
        val file = File(ownerDir(owner), "$name/$META_FILE")
        if (!file.isFile) return null
        val yml = YamlConfiguration.loadConfiguration(file)
        val box = readBox(yml) ?: return null
        val branch = yml.getString("branch") ?: "master"
        val head = yml.getString("head")?.takeIf { it.isNotBlank() }

        val branches = LinkedHashMap<String, String>()
        yml.getConfigurationSection("branches")?.getKeys(false)?.forEach { key ->
            yml.getString("branches.$key")?.takeIf { it.isNotBlank() }?.let { branches[key] = it }
        }
        if (branches.isEmpty() && head != null) branches[branch] = head

        return Repository(
            directory = file.parentFile,
            world = yml.getString("world") ?: "world",
            owner = yml.getString("owner")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: owner,
            ownerName = yml.getString("owner-name") ?: "?",
            box = box,
            branch = branch,
            head = head,
            branches = branches,
        )
    }

    fun create(owner: UUID, ownerName: String, world: String, name: String, box: BlockBox): Repository {
        val directory = File(ownerDir(owner), name)
        File(directory, "commits").mkdirs()
        File(directory, "objects").mkdirs()
        val repository = Repository(directory, world, owner, ownerName, box)
        save(repository)
        return repository
    }

    fun save(repository: Repository) {
        val yml = YamlConfiguration()
        yml.set("world", repository.world)
        yml.set("owner", repository.owner.toString())
        yml.set("owner-name", repository.ownerName)
        yml.set("branch", repository.branch)
        yml.set("head", repository.head)
        yml.set("branches", LinkedHashMap(repository.branches))
        yml.set("bounds.min", listOf(repository.box.min.x, repository.box.min.y, repository.box.min.z))
        yml.set("bounds.max", listOf(repository.box.max.x, repository.box.max.y, repository.box.max.z))
        yml.save(File(repository.directory, META_FILE))
    }

    fun activeName(owner: UUID): String? = active[owner]

    fun setActive(owner: UUID, name: String) {
        active[owner] = name
    }

    fun writeCommit(repository: Repository, commit: Commit) {
        val yml = YamlConfiguration()
        yml.set("id", commit.id)
        yml.set("parent", commit.parent)
        yml.set("snapshot", commit.snapshot)
        yml.set("message", commit.message)
        yml.set("author", commit.author)
        yml.set("timestamp", commit.timestamp)
        yml.save(File(repository.commits, "${commit.id}.yml"))
    }

    fun loadCommit(repository: Repository, id: String): Commit? {
        val file = File(repository.commits, "$id.yml")
        if (!file.isFile) return null
        val yml = YamlConfiguration.loadConfiguration(file)
        return Commit(
            id = yml.getString("id") ?: id,
            parent = yml.getString("parent")?.takeIf { it.isNotBlank() },
            snapshot = yml.getString("snapshot") ?: return null,
            message = yml.getString("message") ?: "",
            author = yml.getString("author") ?: "?",
            timestamp = yml.getLong("timestamp"),
        )
    }

    fun history(repository: Repository, limit: Int = Int.MAX_VALUE): List<Commit> {
        val result = ArrayList<Commit>()
        val seen = HashSet<String>()
        var id = repository.head
        while (id != null && result.size < limit && seen.add(id)) {
            val commit = loadCommit(repository, id) ?: break
            result += commit
            id = commit.parent
        }
        return result
    }

    fun newCommitId(parent: String?, snapshot: String, message: String, author: String, timestamp: Long): String =
        sha256Hex(listOf(parent ?: "-", snapshot, message, author, timestamp.toString()).joinToString("|").toByteArray())
            .substring(0, 12)

    private fun ownerDir(owner: UUID): File = File(File(dataFolder, "repos"), owner.toString())

    private fun readBox(yml: YamlConfiguration): BlockBox? {
        val min = yml.getIntegerList("bounds.min")
        val max = yml.getIntegerList("bounds.max")
        if (min.size != 3 || max.size != 3) return null
        return BlockBox.of(
            BlockPos(min[0], min[1], min[2]),
            BlockPos(max[0], max[1], max[2]),
        )
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val CHUNK = 16

class SnapshotStore(private val repository: Repository) {

    fun write(clipboard: Clipboard, box: BlockBox): String {
        repository.objects.mkdirs()
        val layout = ChunkLayout(box)
        val hashes = ArrayList<String>(layout.count)
        for (index in 0 until layout.count) {
            val local = layout.localBox(index)
            val chunk = WorldEditBridge.emptyClipboard(local)
            WorldEditBridge.copyTo(clipboard, layout.absoluteBox(index), chunk, local)
            val hash = contentHash(chunk, local)
            val file = File(repository.objects, "$hash.schem")
            if (!file.isFile) {
                val out = ByteArrayOutputStream()
                WorldEditBridge.write(chunk, out)
                file.writeBytes(out.toByteArray())
            }
            hashes += hash
        }
        val manifest = hashes.joinToString("\n")
        val manifestHash = sha256Hex(manifest.toByteArray())
        val manifestFile = File(repository.objects, "$manifestHash.mf")
        if (!manifestFile.isFile) manifestFile.writeText(manifest)
        return manifestHash
    }

    fun read(manifestHash: String): Clipboard {
        if (!isChunked(manifestHash)) return readObject(manifestHash)
        val layout = ChunkLayout(repository.box)
        val hashes = manifest(manifestHash)
        val whole = WorldEditBridge.emptyClipboard(repository.box)
        for (index in 0 until layout.count) {
            WorldEditBridge.copyTo(readObject(hashes[index]), layout.localBox(index), whole, layout.absoluteBox(index))
        }
        return whole
    }

    fun readBlockState(manifestHash: String, rel: BlockPos): String {
        if (!isChunked(manifestHash)) return ClipboardBlockSource(readObject(manifestHash)).stateAt(rel)
        val layout = ChunkLayout(repository.box)
        val index = layout.indexFor(rel)
        val chunk = readObject(manifest(manifestHash)[index])
        return ClipboardBlockSource(chunk).stateAt(rel - layout.offsetOf(index))
    }

    fun diff(a: String, b: String): List<BlockChange> {
        if (!isChunked(a) || !isChunked(b)) {
            return DiffEngine.diff(repository.box, ClipboardBlockSource(read(a)), ClipboardBlockSource(read(b)))
        }
        val layout = ChunkLayout(repository.box)
        val hashesA = manifest(a)
        val hashesB = manifest(b)
        val changes = ArrayList<BlockChange>()
        for (index in 0 until layout.count) {
            if (hashesA[index] == hashesB[index]) continue
            val offset = layout.offsetOf(index)
            val before = ClipboardBlockSource(readObject(hashesA[index]))
            val after = ClipboardBlockSource(readObject(hashesB[index]))
            for (change in DiffEngine.diff(layout.localBox(index), before, after)) {
                changes += change.copy(pos = change.pos + offset)
            }
        }
        return changes
    }

    private fun isChunked(snapshotHash: String): Boolean = File(repository.objects, "$snapshotHash.mf").isFile

    private fun manifest(manifestHash: String): List<String> =
        File(repository.objects, "$manifestHash.mf").readText().split("\n")

    private fun readObject(hash: String): Clipboard =
        File(repository.objects, "$hash.schem").inputStream().use { WorldEditBridge.read(it) }

    private fun contentHash(chunk: Clipboard, box: BlockBox): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var hasNbt = false
        for (y in box.min.y..box.max.y) {
            for (z in box.min.z..box.max.z) {
                for (x in box.min.x..box.max.x) {
                    val block = chunk.getFullBlock(BlockVector3.at(x, y, z))
                    digest.update(block.asString.toByteArray())
                    digest.update(0)
                    if (!block.nbtId.isNullOrEmpty()) hasNbt = true
                }
            }
        }
        if (hasNbt) digest.update(UUID.randomUUID().toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private class ChunkLayout(val box: BlockBox) {

    private val nx = (box.sizeX + CHUNK - 1) / CHUNK
    private val ny = (box.sizeY + CHUNK - 1) / CHUNK
    private val nz = (box.sizeZ + CHUNK - 1) / CHUNK
    val count = nx * ny * nz

    fun indexFor(rel: BlockPos): Int = (rel.y / CHUNK * nz + rel.z / CHUNK) * nx + rel.x / CHUNK

    fun offsetOf(index: Int): BlockPos {
        val cx = index % nx
        val rest = index / nx
        return BlockPos(cx * CHUNK, rest / nz * CHUNK, rest % nz * CHUNK)
    }

    fun localBox(index: Int): BlockBox {
        val offset = offsetOf(index)
        return BlockBox(
            BlockPos(0, 0, 0),
            BlockPos(
                minOf(CHUNK - 1, box.sizeX - 1 - offset.x),
                minOf(CHUNK - 1, box.sizeY - 1 - offset.y),
                minOf(CHUNK - 1, box.sizeZ - 1 - offset.z),
            ),
        )
    }

    fun absoluteBox(index: Int): BlockBox {
        val offset = offsetOf(index)
        return BlockBox(box.min + offset, box.min + offset + localBox(index).max)
    }
}
