package io.github.flamy69.flamyplayer.data

/** A directory that contains audio, directly or somewhere below it. */
class FolderNode(
    /** Absolute path of the directory. */
    val path: String,
    /** Label relative to the parent node; may span several levels, e.g. `emulated/0`. */
    val name: String,
    /** Number of audio files directly inside this directory. */
    val directCount: Int,
    /** Number of audio files inside this directory and all of its subdirectories. */
    val totalCount: Int,
    val children: List<FolderNode>,
) {
    /** The nodes leading from this node down to [target], or an empty list if it is not below this node. */
    fun chainTo(target: String): List<FolderNode> {
        val chain = ArrayList<FolderNode>()
        var node = this
        while (true) {
            chain += node
            if (node.path == target) return chain
            node = node.children.firstOrNull { target == it.path || target.startsWith(it.path + "/") }
                ?: return emptyList()
        }
    }

    /** Number of audio files in [folder] and below it, whether or not it is a node of its own. */
    fun countUnder(folder: String): Int = when {
        path == folder || path.startsWith("$folder/") -> totalCount
        folder.startsWith("$path/") -> children.sumOf { it.countUnder(folder) }
        else -> 0
    }
}

object FolderTree {

    private class Builder(val path: String) {
        var direct = 0
        val children = HashMap<String, Builder>()
    }

    /**
     * Builds the tree of directories from a map of `directory path -> number of audio files`.
     * Chains of directories that hold nothing but a single subdirectory are folded into one
     * node, which makes the uninteresting `/storage/emulated/0` prefix disappear.
     *
     * Returns `null` when there are no audio files at all.
     */
    fun build(folderCounts: Map<String, Int>): FolderNode? {
        if (folderCounts.isEmpty()) return null
        val root = Builder("")
        for ((folder, count) in folderCounts) {
            var node = root
            for (segment in folder.split('/')) {
                if (segment.isEmpty()) continue
                node = node.children.getOrPut(segment) { Builder(node.path + "/" + segment) }
            }
            node.direct += count
        }
        return finish(root, "")
    }

    private fun finish(builder: Builder, name: String): FolderNode {
        var node = builder
        var label = name
        while (node.direct == 0 && node.children.size == 1) {
            val (segment, child) = node.children.entries.first()
            label = if (label.isEmpty()) segment else "$label/$segment"
            node = child
        }
        val children = node.children.entries
            .map { (segment, child) -> finish(child, segment) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        return FolderNode(
            path = node.path,
            name = label,
            directCount = node.direct,
            totalCount = node.direct + children.sumOf { it.totalCount },
            children = children,
        )
    }
}

/** Rules for a set of selected folders, where selecting a folder includes everything below it. */
object FolderSelection {

    fun covers(selected: Set<String>, folder: String): Boolean =
        selected.any { folder == it || folder.startsWith("$it/") }

    fun coveredByAncestor(selected: Set<String>, folder: String): Boolean =
        selected.any { folder.startsWith("$it/") }

    /** Selects or unselects [folder]. Selecting drops selected subfolders, which become redundant. */
    fun toggle(selected: Set<String>, folder: String): Set<String> =
        if (folder in selected) {
            selected - folder
        } else {
            selected.filterNotTo(HashSet()) { it.startsWith("$folder/") }.apply { add(folder) }
        }

    /** The tracks that live in one of the [selected] folders, in their original order. */
    fun tracksIn(tracks: List<Track>, selected: Set<String>): List<Track> {
        if (selected.isEmpty()) return emptyList()
        val included = HashMap<String, Boolean>()
        return tracks.filter { track -> included.getOrPut(track.folder) { covers(selected, track.folder) } }
    }
}

/** Sorts tracks folder by folder, each folder in natural file name order. */
fun sortByFolderAndName(tracks: List<Track>): List<Track> {
    val byFolder = tracks.groupBy { it.folder }
    val result = ArrayList<Track>(tracks.size)
    for (folder in byFolder.keys.sortedWith(PathOrder)) {
        result += byFolder.getValue(folder).sortedWith(compareBy(NaturalOrder) { it.fileName })
    }
    return result
}
