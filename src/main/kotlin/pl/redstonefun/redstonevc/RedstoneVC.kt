package pl.redstonefun.redstonevc

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.text.SimpleDateFormat
import java.util.Date

class RedstoneVC : JavaPlugin() {

    private var highlights: HighlightManager? = null

    override fun onEnable() {
        val manager = HighlightManager(this)
        highlights = manager
        RvCommand(this, RepoManager(dataFolder), manager).register()
    }

    override fun onDisable() {
        highlights?.clearAll()
    }
}

class RvCommand(
    private val plugin: Plugin,
    private val repos: RepoManager,
    private val highlights: HighlightManager,
) : BasicCommand {

    private var diffHighlights: List<Highlight> = emptyList()
    private var inspectHighlight: Highlight? = null

    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register("rv", "Simple version control for a build region.", this)
        }
    }

    override fun permission(): String = "rv.use"

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val sender = source.sender
        if (sender !is Player) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED))
            return
        }
        clearDiffHighlights()

        val sub = args.firstOrNull()?.lowercase()
        if (sub == null) {
            usage(sender)
            return
        }
        when (sub) {
            "init" -> { init(sender, args.getOrNull(1)); return }
            "list" -> { listRepos(sender); return }
            "use" -> { use(sender, args.getOrNull(1)); return }
            "tp" -> { teleport(sender, args.getOrNull(1)); return }
        }

        val repo = activeRepo(sender) ?: run {
            sender.sendMessage(Component.text("No active repository. Use /rv init <name> or /rv use <name>.", NamedTextColor.RED))
            return
        }
        when (sub) {
            "commit" -> commit(sender, repo, args.drop(1).joinToString(" ").trim())
            "log" -> log(sender, repo, args.getOrNull(1)?.toIntOrNull())
            "status" -> status(sender, repo)
            "diff" -> diff(sender, repo, args.drop(1))
            "inspect" -> inspect(sender, repo)
            "restore" -> restore(sender, repo, args.getOrNull(1))
            "branch" -> branch(sender, repo, args.getOrNull(1))
            "switch" -> switch(sender, repo, args.getOrNull(1))
            else -> {
                sender.sendMessage(Component.text("Unknown command '$sub'.", NamedTextColor.RED))
                usage(sender)
            }
        }
    }

    private fun init(player: Player, name: String?): Boolean {
        if (name == null || !NAME_PATTERN.matches(name)) {
            player.sendMessage(Component.text("Usage: /rv init <name>", NamedTextColor.RED))
            return true
        }
        if (repos.load(player.uniqueId, name) != null) {
            player.sendMessage(Component.text("You already have a repository '$name'.", NamedTextColor.RED))
            return true
        }
        val box = WorldEditBridge.selectionBounds(player) ?: run {
            player.sendMessage(Component.text("Select a region with your WorldEdit wand first.", NamedTextColor.RED))
            return true
        }
        repos.create(player.uniqueId, player.name, player.world.name, name, box)
        repos.setActive(player.uniqueId, name)
        player.sendMessage(
            Component.text(
                "Initialized '$name' in ${player.world.name} — ${box.sizeX}x${box.sizeY}x${box.sizeZ}.",
                NamedTextColor.GREEN,
            ),
        )
        return true
    }

    private fun use(player: Player, name: String?): Boolean {
        if (name == null) {
            player.sendMessage(Component.text("Usage: /rv use <name>", NamedTextColor.RED))
            return true
        }
        val repo = repos.load(player.uniqueId, name) ?: run {
            player.sendMessage(Component.text("You don't have a repository named '$name'.", NamedTextColor.RED))
            return true
        }
        repos.setActive(player.uniqueId, name)
        player.sendMessage(Component.text("Now using '${repo.name}' (${repo.world}).", NamedTextColor.GREEN))
        return true
    }

    private fun listRepos(player: Player): Boolean {
        val names = repos.list(player.uniqueId)
        if (names.isEmpty()) {
            player.sendMessage(Component.text("You have no repositories. Use /rv init <name>.", NamedTextColor.GRAY))
            return true
        }
        val active = repos.activeName(player.uniqueId)
        player.sendMessage(Component.text("Your repositories:", NamedTextColor.GRAY))
        for (name in names) {
            val repo = repos.load(player.uniqueId, name) ?: continue
            val box = repo.box
            val current = name == active
            player.sendMessage(
                Component.text(if (current) "* " else "  ", NamedTextColor.GREEN)
                    .append(Component.text(name, if (current) NamedTextColor.GREEN else NamedTextColor.WHITE))
                    .append(Component.text("  ${repo.world}", NamedTextColor.AQUA))
                    .append(Component.text("  (${box.min.x}, ${box.min.y}, ${box.min.z})", NamedTextColor.YELLOW))
                    .append(Component.text("  ${box.sizeX}x${box.sizeY}x${box.sizeZ}", NamedTextColor.GRAY))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport", NamedTextColor.GREEN)))
                    .clickEvent(ClickEvent.runCommand("/rv tp $name")),
            )
        }
        return true
    }

    private fun teleport(player: Player, name: String?): Boolean {
        if (name == null) {
            player.sendMessage(Component.text("Usage: /rv tp <name>", NamedTextColor.RED))
            return true
        }
        val repo = repos.load(player.uniqueId, name) ?: run {
            player.sendMessage(Component.text("You don't have a repository named '$name'.", NamedTextColor.RED))
            return true
        }
        val world = repoWorld(repo) ?: run {
            player.sendMessage(Component.text("World '${repo.world}' is not loaded.", NamedTextColor.RED))
            return true
        }
        val box = repo.box
        player.teleport(
            Location(
                world,
                box.min.x + box.sizeX / 2.0 + 0.5,
                (box.max.y + 1).toDouble(),
                box.min.z + box.sizeZ / 2.0 + 0.5,
            ),
        )
        player.sendMessage(Component.text("Teleported to '$name'.", NamedTextColor.GREEN))
        return true
    }

    private fun commit(player: Player, repo: Repository, message: String): Boolean {
        if (message.isEmpty()) {
            player.sendMessage(Component.text("Usage: /rv commit <message>", NamedTextColor.RED))
            return true
        }
        val world = repoWorld(repo) ?: run {
            player.sendMessage(Component.text("World '${repo.world}' is not loaded.", NamedTextColor.RED))
            return true
        }
        val store = SnapshotStore(repo)
        val head = repo.head?.let { repos.loadCommit(repo, it) }
        val current = WorldEditBridge.capture(world, repo.box)

        if (head != null) {
            val changes = DiffEngine.diff(repo.box, ClipboardBlockSource(store.read(head.snapshot)), ClipboardBlockSource(current))
            if (changes.isEmpty()) {
                player.sendMessage(Component.text("Nothing to commit.", NamedTextColor.GRAY))
                return true
            }
        }

        val snapshot = store.write(current, repo.box)
        val now = System.currentTimeMillis()
        val commit = Commit(
            id = repos.newCommitId(repo.head, snapshot, message, player.name, now),
            parent = repo.head,
            snapshot = snapshot,
            message = message,
            author = player.name,
            timestamp = now,
        )
        repo.pointBranch(commit.id)
        repos.writeCommit(repo, commit)
        repos.save(repo)
        player.sendMessage(Component.text("Committed ${commit.id} on ${repo.branch}: $message", NamedTextColor.GREEN))
        return true
    }

    private fun log(player: Player, repo: Repository, limit: Int?): Boolean {
        val history = repos.history(repo, limit?.coerceAtLeast(1) ?: 10)
        if (history.isEmpty()) {
            player.sendMessage(Component.text("No commits yet.", NamedTextColor.GRAY))
            return true
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm")
        for (commit in history) {
            val date = format.format(Date(commit.timestamp))
            val tooltip = Component.text("Author: ${commit.author}")
                .append(Component.newline())
                .append(Component.text("Date: $date"))
                .append(Component.newline())
                .append(Component.text(commit.id))

            player.sendMessage(
                Component.text(commit.id, NamedTextColor.YELLOW)
                    .append(decoration(repo, commit))
                    .append(Component.text("  ${commit.message}", NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(tooltip))
                    .clickEvent(ClickEvent.copyToClipboard(commit.id)),
            )
        }
        return true
    }

    private fun decoration(repo: Repository, commit: Commit): Component {
        val tips = repo.branches.filterValues { it == commit.id }.keys.sorted()
        if (tips.isEmpty()) return Component.empty()

        var decoration = Component.text(" (", NamedTextColor.YELLOW)
        tips.forEachIndexed { index, branch ->
            if (index > 0) decoration = decoration.append(Component.text(", ", NamedTextColor.YELLOW))
            decoration = if (branch == repo.branch) {
                decoration
                    .append(Component.text("HEAD", NamedTextColor.AQUA))
                    .append(Component.text(" -> ", NamedTextColor.YELLOW))
                    .append(Component.text(branch, NamedTextColor.GREEN))
            } else {
                decoration.append(Component.text(branch, NamedTextColor.GREEN))
            }
        }
        return decoration.append(Component.text(")", NamedTextColor.YELLOW))
    }

    private fun status(player: Player, repo: Repository): Boolean {
        val head = repo.head?.let { repos.loadCommit(repo, it) } ?: run {
            player.sendMessage(Component.text("On ${repo.branch}: no commits yet.", NamedTextColor.GRAY))
            return true
        }
        val world = repoWorld(repo) ?: run {
            player.sendMessage(Component.text("World '${repo.world}' is not loaded.", NamedTextColor.RED))
            return true
        }
        player.sendMessage(Component.text("On ${repo.branch} @ ${head.id}", NamedTextColor.GRAY))
        report(player, workingDiff(repo, world, head.snapshot))
        return true
    }

    private fun diff(player: Player, repo: Repository, refs: List<String>): Boolean {
        val changes = when (refs.size) {
            0 -> {
                val head = repo.head?.let { repos.loadCommit(repo, it) } ?: run {
                    player.sendMessage(Component.text("No commits yet.", NamedTextColor.GRAY))
                    return true
                }
                val world = repoWorld(repo) ?: run {
                    player.sendMessage(Component.text("World '${repo.world}' is not loaded.", NamedTextColor.RED))
                    return true
                }
                workingDiff(repo, world, head.snapshot)
            }
            1 -> {
                val ref = resolve(repo, refs[0]) ?: run {
                    player.sendMessage(Component.text("Unknown ref '${refs[0]}'.", NamedTextColor.RED))
                    return true
                }
                val world = repoWorld(repo) ?: run {
                    player.sendMessage(Component.text("World '${repo.world}' is not loaded.", NamedTextColor.RED))
                    return true
                }
                workingDiff(repo, world, ref.snapshot)
            }
            else -> {
                val a = resolve(repo, refs[0]) ?: run {
                    player.sendMessage(Component.text("Unknown ref '${refs[0]}'.", NamedTextColor.RED))
                    return true
                }
                val b = resolve(repo, refs[1]) ?: run {
                    player.sendMessage(Component.text("Unknown ref '${refs[1]}'.", NamedTextColor.RED))
                    return true
                }
                SnapshotStore(repo).diff(a.snapshot, b.snapshot)
            }
        }
        if (changes.isEmpty()) {
            player.sendMessage(Component.text("No changes.", NamedTextColor.GRAY))
            return true
        }
        renderDiff(player, repo, changes)
        return true
    }

    private fun inspect(player: Player, repo: Repository): Boolean {
        if (player.world.name != repo.world) {
            player.sendMessage(Component.text("You must be in '${repo.world}' to inspect this repository.", NamedTextColor.RED))
            return true
        }
        val target = player.getTargetBlockExact(RANGE)
        if (target == null || target.type.isAir) {
            player.sendMessage(Component.text("Look at a block within $RANGE blocks.", NamedTextColor.RED))
            return true
        }
        val pos = BlockPos(target.x, target.y, target.z)
        if (!repo.box.contains(pos)) {
            player.sendMessage(Component.text("That block is outside the project region.", NamedTextColor.RED))
            return true
        }

        val current = WorldEditBridge.blockState(player.world, pos)
        inspectHighlight?.let { highlights.clear(it) }
        inspectHighlight = highlights.show(player.world, listOf(pos), HighlightColor.WHITE, INSPECT_TICKS, HighlightManager.CELL_SCALE)

        val rel = pos - repo.box.min
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val result = runCatching { blockHistory(repo, rel) }
            plugin.server.scheduler.runTask(plugin, Runnable {
                result.onSuccess { reportHistory(player, pos, current, it) }
                    .onFailure { player.sendMessage(Component.text("Inspect failed: ${it.message}", NamedTextColor.RED)) }
            })
        })
        return true
    }

    private fun restore(player: Player, repo: Repository, ref: String?): Boolean {
        if (ref == null) {
            player.sendMessage(Component.text("Usage: /rv restore <ref>", NamedTextColor.RED))
            return true
        }
        val commit = resolve(repo, ref) ?: run {
            player.sendMessage(Component.text("Unknown ref '$ref'.", NamedTextColor.RED))
            return true
        }
        val world = repoWorld(repo) ?: run {
            player.sendMessage(Component.text("World '${repo.world}' is not loaded.", NamedTextColor.RED))
            return true
        }
        WorldEditBridge.paste(world, repo.box, SnapshotStore(repo).read(commit.snapshot))
        player.sendMessage(Component.text("Restored ${commit.id} (branch untouched).", NamedTextColor.GREEN))
        return true
    }

    private fun branch(player: Player, repo: Repository, name: String?): Boolean {
        if (name == null) {
            player.sendMessage(Component.text("Branches:", NamedTextColor.GRAY))
            for ((branch, id) in repo.branches.toSortedMap()) {
                val current = branch == repo.branch
                player.sendMessage(
                    Component.text(if (current) "* " else "  ", NamedTextColor.GREEN)
                        .append(Component.text(branch, if (current) NamedTextColor.GREEN else NamedTextColor.WHITE))
                        .append(Component.text(" -> ", NamedTextColor.YELLOW))
                        .append(Component.text(id, NamedTextColor.YELLOW)),
                )
            }
            return true
        }
        if (!NAME_PATTERN.matches(name)) {
            player.sendMessage(Component.text("Invalid branch name.", NamedTextColor.RED))
            return true
        }
        if (repo.branches.containsKey(name)) {
            player.sendMessage(Component.text("Branch '$name' already exists.", NamedTextColor.RED))
            return true
        }
        val head = repo.head ?: run {
            player.sendMessage(Component.text("No commits yet.", NamedTextColor.RED))
            return true
        }
        repo.branches[name] = head
        repos.save(repo)
        player.sendMessage(Component.text("Created branch '$name' at $head.", NamedTextColor.GREEN))
        return true
    }

    private fun switch(player: Player, repo: Repository, name: String?): Boolean {
        if (name == null) {
            player.sendMessage(Component.text("Usage: /rv switch <branch>", NamedTextColor.RED))
            return true
        }
        val id = repo.branches[name] ?: run {
            player.sendMessage(Component.text("No branch '$name'.", NamedTextColor.RED))
            return true
        }
        if (repo.branch == name) {
            player.sendMessage(Component.text("Already on '$name'.", NamedTextColor.GRAY))
            return true
        }

        val world = repoWorld(repo)
        val head = repo.head?.let { repos.loadCommit(repo, it) }
        if (world != null && head != null && workingDiff(repo, world, head.snapshot).isNotEmpty()) {
            player.sendMessage(Component.text("You have uncommitted changes. Commit them or run /rv restore HEAD first.", NamedTextColor.RED))
            return true
        }

        repo.branch = name
        repo.pointBranch(id)
        repos.save(repo)

        val target = repos.loadCommit(repo, id)
        if (world != null && target != null) {
            WorldEditBridge.paste(world, repo.box, SnapshotStore(repo).read(target.snapshot))
            player.sendMessage(Component.text("Switched to '$name' and updated the world to ${target.id}.", NamedTextColor.GREEN))
        } else {
            player.sendMessage(Component.text("Switched to '$name'.", NamedTextColor.GREEN))
        }
        return true
    }

    private data class BlockRevision(val commit: Commit, val from: String, val to: String)

    private fun repoWorld(repo: Repository): World? = plugin.server.getWorld(repo.world)

    private fun workingDiff(repo: Repository, world: World, snapshot: String): List<BlockChange> =
        DiffEngine.diff(
            repo.box,
            ClipboardBlockSource(SnapshotStore(repo).read(snapshot)),
            WorldEditBridge.worldSource(world, repo.box),
        )

    private fun blockHistory(repo: Repository, rel: BlockPos): List<BlockRevision> {
        val store = SnapshotStore(repo)
        val cache = HashMap<String, String>()
        fun stateAt(snapshot: String): String = cache.getOrPut(snapshot) { store.readBlockState(snapshot, rel) }

        val revisions = ArrayList<BlockRevision>()
        for (commit in repos.history(repo, HISTORY_LIMIT)) {
            val parent = commit.parent?.let { repos.loadCommit(repo, it) } ?: continue
            val from = stateAt(parent.snapshot)
            val to = stateAt(commit.snapshot)
            if (from != to) revisions += BlockRevision(commit, from, to)
        }
        return revisions
    }

    private fun activeRepo(player: Player): Repository? {
        repos.activeName(player.uniqueId)?.let { name ->
            repos.load(player.uniqueId, name)?.let { return it }
        }
        val names = repos.list(player.uniqueId)
        if (names.size == 1) {
            repos.setActive(player.uniqueId, names[0])
            return repos.load(player.uniqueId, names[0])
        }
        return null
    }

    private fun resolve(repo: Repository, ref: String): Commit? {
        repo.branches[ref]?.let { return repos.loadCommit(repo, it) }
        if (ref.equals("HEAD", ignoreCase = true)) return repo.head?.let { repos.loadCommit(repo, it) }
        repos.loadCommit(repo, ref)?.let { return it }
        return repos.history(repo).firstOrNull { it.id.startsWith(ref, ignoreCase = true) }
    }

    private fun renderDiff(player: Player, repo: Repository, changes: List<BlockChange>) {
        val world = repoWorld(repo) ?: return
        val added = ArrayList<BlockPos>()
        val removed = ArrayList<BlockPos>()
        val modified = ArrayList<BlockPos>()
        for (change in changes.take(MAX_CHANGES)) {
            val pos = repo.box.min + change.pos
            when (change.type) {
                ChangeType.ADDED -> added += pos
                ChangeType.REMOVED -> removed += pos
                ChangeType.MODIFIED -> modified += pos
            }
        }
        diffHighlights = listOf(
            highlights.show(world, added, HighlightColor.GREEN, HIGHLIGHT_TICKS, HighlightManager.CELL_SCALE),
            highlights.show(world, removed, HighlightColor.RED, HIGHLIGHT_TICKS, HighlightManager.CELL_SCALE),
            highlights.show(world, modified, HighlightColor.YELLOW, HIGHLIGHT_TICKS, HighlightManager.CELL_SCALE),
        )
        report(player, changes)
        if (changes.size > MAX_CHANGES) {
            player.sendMessage(Component.text("Showing the first $MAX_CHANGES changes.", NamedTextColor.GRAY))
        }
    }

    private fun clearDiffHighlights() {
        diffHighlights.forEach { highlights.clear(it) }
        diffHighlights = emptyList()
    }

    private fun report(player: Player, changes: List<BlockChange>) {
        player.sendMessage(
            Component.text("Changes: ", NamedTextColor.GRAY)
                .append(Component.text("+${changes.count { it.type == ChangeType.ADDED }}", NamedTextColor.GREEN))
                .append(Component.text(" -${changes.count { it.type == ChangeType.REMOVED }}", NamedTextColor.RED))
                .append(Component.text(" ~${changes.count { it.type == ChangeType.MODIFIED }}", NamedTextColor.YELLOW)),
        )
    }

    private fun reportHistory(player: Player, pos: BlockPos, current: String, revisions: List<BlockRevision>) {
        player.sendMessage(Component.text("Block at ${pos.x}, ${pos.y}, ${pos.z}:", NamedTextColor.AQUA))
        player.sendMessage(Component.text(short(current), NamedTextColor.GRAY))
        if (revisions.isEmpty()) {
            player.sendMessage(Component.text("No changes recorded for this block.", NamedTextColor.AQUA))
            return
        }
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm")
        player.sendMessage(Component.text("History [${revisions.size}]:", NamedTextColor.AQUA))
        for (revision in revisions) {
            player.sendMessage(Component.text("${revision.commit.id}  ${format.format(Date(revision.commit.timestamp))}  ${revision.commit.author}", NamedTextColor.YELLOW))
            player.sendMessage(Component.text("  - ", NamedTextColor.RED).append(Component.text(short(revision.from), LIGHT_RED)))
            player.sendMessage(Component.text("  + ", NamedTextColor.GREEN).append(Component.text(short(revision.to), LIGHT_GREEN)))
        }
    }

    private fun short(state: String): String = state.removePrefix("minecraft:")

    private fun usage(player: Player) {
            player.sendMessage(Component.text("/rv init <name>", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv list", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv use <name>", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv tp <name>", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv commit <msg>", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv log [n]", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv status", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv diff [ref] [ref]", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv restore <ref>", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv inspect", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv branch [name]", NamedTextColor.GRAY))
            player.sendMessage(Component.text("/rv switch <name>", NamedTextColor.GRAY))
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): Collection<String> {
        val sender = source.sender
        if (sender !is Player) return emptyList()
        if (args.isEmpty()) return SUBCOMMANDS

        val repo = activeRepo(sender)
        val options: List<String> = when (args.size) {
            1 -> SUBCOMMANDS
            2 -> when (args[0].lowercase()) {
                "use", "tp" -> repos.list(sender.uniqueId)
                "switch" -> repo?.branches?.keys?.toList() ?: emptyList()
                "diff", "restore" -> refs(repo)
                else -> emptyList()
            }
            3 -> if (args[0].equals("diff", ignoreCase = true)) refs(repo) else emptyList()
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), ignoreCase = true) }
    }

    private fun refs(repo: Repository?): List<String> {
        if (repo == null) return listOf("HEAD")
        return buildList {
            add("HEAD")
            addAll(repo.branches.keys)
            addAll(repos.history(repo, 50).map { it.id })
        }
    }

    private companion object {
        private val NAME_PATTERN = Regex("[A-Za-z0-9._-]+")
        private val SUBCOMMANDS =
            listOf("init", "list", "use", "tp", "commit", "log", "status", "diff", "restore", "inspect", "branch", "switch")

        private const val RANGE = 10
        private const val HIGHLIGHT_TICKS = 900L
        private const val INSPECT_TICKS = 60L
        private const val HISTORY_LIMIT = 200
        private const val MAX_CHANGES = 3000

        private val LIGHT_RED = TextColor.color(0xFF9C9C)
        private val LIGHT_GREEN = TextColor.color(0x9CFF9C)
    }
}
