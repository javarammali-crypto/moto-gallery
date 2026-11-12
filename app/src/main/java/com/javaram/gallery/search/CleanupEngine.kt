package com.javaram.gallery.search

import com.javaram.gallery.data.Media

enum class CleanupKind(val title: String, val subtitle: String) {
    Duplicates("Duplicates", "Exact copies of the same photo"),
    Similar("Similar shots", "Bursts and near-identical photos — keep the best"),
    Blurry("Blurry photos", "Photos that look out of focus"),
    LargeVideos("Large videos", "Videos over 100 MB"),
    Screenshots("Old screenshots", "Screenshots older than 30 days"),
}

/** A set of photos where one is suggested to keep and the rest to delete. */
data class CleanupGroup(val keep: Media, val others: List<Media>)

data class CleanupReport(
    val duplicates: List<CleanupGroup>,
    val similar: List<CleanupGroup>,
    val blurry: List<Media>,
    val largeVideos: List<Media>,
    val screenshots: List<Media>,
) {
    fun groups(kind: CleanupKind) = when (kind) {
        CleanupKind.Duplicates -> duplicates
        CleanupKind.Similar -> similar
        else -> emptyList()
    }

    fun list(kind: CleanupKind) = when (kind) {
        CleanupKind.Blurry -> blurry
        CleanupKind.LargeVideos -> largeVideos
        CleanupKind.Screenshots -> screenshots
        else -> emptyList()
    }

    fun count(kind: CleanupKind) = when (kind) {
        CleanupKind.Duplicates -> duplicates.sumOf { it.others.size }
        CleanupKind.Similar -> similar.sumOf { it.others.size }
        else -> list(kind).size
    }

    fun bytes(kind: CleanupKind) = when (kind) {
        CleanupKind.Duplicates -> duplicates.sumOf { g -> g.others.sumOf { it.size } }
        CleanupKind.Similar -> similar.sumOf { g -> g.others.sumOf { it.size } }
        else -> list(kind).sumOf { it.size }
    }

    companion object {
        val Empty = CleanupReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

object CleanupEngine {
    private const val SIMILAR_BITS = 8
    private const val SIMILAR_WINDOW_MS = 3 * 60_000L
    const val BLUR_LIMIT = 28f

    private fun isScreenshot(m: Media) =
        m.bucketName.contains("screenshot", true) || m.relativePath.contains("screenshot", true)

    private fun score(m: Media, q: SearchIndex.Quality?): Double =
        (q?.sharp?.toDouble() ?: 0.0).coerceAtMost(4000.0) * 2 + m.width.toDouble() * m.height / 1e5 + if (m.isFavorite) 1e6 else 0.0

    fun analyse(media: List<Media>, quality: Map<Long, SearchIndex.Quality>): CleanupReport {
        val photos = media.filter { !it.isVideo }

        // Exact duplicates: same size and dimensions and the same fingerprint.
        val dupGroups = photos
            .groupBy { Triple(it.size, it.width * 100_000L + it.height, quality[it.id]?.dhash) }
            .values.filter { it.size > 1 && (quality[it[0].id]?.sharp ?: -1f) >= 0f }
            .map { g ->
                val keep = g.minWith(compareBy<Media>({ !it.relativePath.startsWith("DCIM", true) }, { it.dateModified }))
                CleanupGroup(keep, g.filter { it.id != keep.id })
            }
        val inDup = dupGroups.flatMap { it.others + it.keep }.mapTo(HashSet()) { it.id }

        // Similar shots: taken within a few minutes and looking nearly the same.
        val sorted = photos.filter { it.id !in inDup && !isScreenshot(it) && quality[it.id]?.let { q -> q.sharp >= 0 } == true }
            .sortedBy { it.dateTaken }
        val similar = ArrayList<CleanupGroup>()
        var i = 0
        while (i < sorted.size) {
            val group = arrayListOf(sorted[i])
            var j = i + 1
            while (j < sorted.size && sorted[j].dateTaken - group.last().dateTaken <= SIMILAR_WINDOW_MS) {
                val a = quality[group.last().id]!!.dhash
                val b = quality[sorted[j].id]!!.dhash
                if (java.lang.Long.bitCount(a xor b) <= SIMILAR_BITS) group += sorted[j] else break
                j++
            }
            if (group.size > 1) {
                val keep = group.maxBy { score(it, quality[it.id]) }
                similar += CleanupGroup(keep, group.filter { it.id != keep.id })
            }
            i = j
        }

        val blurry = photos.filter {
            val q = quality[it.id]
            q != null && q.sharp in 0f..BLUR_LIMIT && !isScreenshot(it)
        }
        val large = media.filter { it.isVideo && it.size > 100L * 1024 * 1024 }.sortedByDescending { it.size }
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val shots = photos.filter { isScreenshot(it) && it.dateTaken < monthAgo }

        return CleanupReport(
            duplicates = dupGroups.sortedByDescending { g -> g.others.sumOf { it.size } },
            similar = similar.sortedByDescending { it.keep.dateTaken },
            blurry = blurry,
            largeVideos = large,
            screenshots = shots,
        )
    }
}
