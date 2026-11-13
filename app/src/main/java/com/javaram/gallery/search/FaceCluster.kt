package com.javaram.gallery.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Groups every face in the library into people in one go.
 *
 * 1. Each clear face is linked to its closest look-alikes (only links above [FaceMatch.LINK]).
 * 2. "Chinese whispers": every face repeatedly adopts the label most of its links carry.
 *    Unlike adding faces one by one, early mistakes don't snowball and a chain of
 *    look-alikes (siblings, parent and child) doesn't glue different people together.
 * 3. Blurry, tiny or sideways faces join a person only if they clearly match one.
 * 4. Names the user gave are carried over by where the face is in the photo; a group
 *    holding two named people is split between them, and groups with the same name join.
 */
object FaceCluster {

    private const val K = 16
    private const val MAX_CORE = 10_000
    private const val CORE_QUALITY = 0.45f
    private const val EXEMPLARS = 40

    class Result(val people: List<SearchIndex.NewPerson>)

    suspend fun cluster(faces: List<SearchIndex.ClusterFace>, anchors: List<SearchIndex.Anchor>): Result {
        if (faces.isEmpty()) return Result(emptyList())
        val dim = faces[0].emb.size
        val usable = faces.filter { it.emb.size == dim }

        // ---- 1. Clear faces form the graph ----
        val core = usable.filter { it.q >= CORE_QUALITY }.sortedByDescending { it.q }.take(MAX_CORE)
        val n = core.size
        val flat = FloatArray(n * dim)
        core.forEachIndexed { i, f -> System.arraycopy(f.emb, 0, flat, i * dim, dim) }
        val neighbours = nearest(flat, n, dim, core.map { it.mediaId }.toLongArray())

        val adj = Array(n) { HashMap<Int, Float>() }
        for (i in 0 until n) for ((j, s) in neighbours[i]) {
            adj[i][j] = s
            adj[j][i] = s
        }

        // ---- 2. Chinese whispers ----
        val label = IntArray(n) { it }
        val rnd = Random(7)
        val order = (0 until n).toMutableList()
        repeat(25) {
            order.shuffle(rnd)
            var changed = 0
            val weights = HashMap<Int, Float>()
            for (i in order) {
                val links = adj[i]
                if (links.isEmpty()) continue
                weights.clear()
                for ((j, s) in links) weights[label[j]] = (weights[label[j]] ?: 0f) + s
                val best = weights.maxByOrNull { it.value }!!.key
                if (best != label[i]) { label[i] = best; changed++ }
            }
            if (changed == 0) return@repeat
        }

        // Groups of at least two faces from different photos
        val groups = HashMap<Int, MutableList<SearchIndex.ClusterFace>>()
        for (i in 0 until n) groups.getOrPut(label[i]) { ArrayList() } += core[i]
        val clusters = groups.values
            .filter { g -> g.map { it.mediaId }.distinct().size >= 2 }
            .map { Cluster(it.toMutableList()) }
            .toMutableList()
        clusters.forEach { it.refresh() }

        // ---- 3. Everyone else joins only on a clear match ----
        val assigned = HashSet<Long>()
        clusters.forEach { c -> c.faces.forEach { assigned += it.id } }
        for (f in usable) {
            if (f.id in assigned) continue
            var best: Cluster? = null
            var bestScore = -1f
            for (c in clusters) {
                val s = c.match(f.emb)
                if (s > bestScore) { bestScore = s; best = c }
            }
            val need = if (f.q >= CORE_QUALITY) FaceMatch.ASSIGN_CLEAR else FaceMatch.ASSIGN_WEAK
            if (best != null && bestScore >= need && best.faces.none { it.mediaId == f.mediaId }) {
                best.faces += f
                assigned += f.id
            }
        }
        clusters.forEach { it.refresh() }

        // Join groups that are clearly the same person (e.g. a child at different ages)
        mergeClose(clusters)

        // ---- 4. Names ----
        val anchorByMedia = anchors.groupBy { it.mediaId }
        fun anchorOf(f: SearchIndex.ClusterFace): SearchIndex.Anchor? =
            anchorByMedia[f.mediaId]?.maxByOrNull { overlap(it.box, f.box) }?.takeIf { overlap(it.box, f.box) > 0.35f }

        val named = ArrayList<Cluster>()
        for (c in clusters) {
            val tags = c.faces.mapNotNull { f -> anchorOf(f)?.let { f to it } }
            val byName = tags.filter { it.second.name != null }.groupBy { it.second.name!!.trim().lowercase() }
            val strong = byName.filter { it.value.size >= 2 && it.value.size * 5 >= tags.size }
            if (strong.size >= 2) {
                // Two named people ended up together: split the group between them.
                val centres = strong.mapValues { (_, list) -> centroid(list.map { it.first.emb }) }
                val parts = strong.keys.associateWith { ArrayList<SearchIndex.ClusterFace>() }
                for (f in c.faces) {
                    val tagged = anchorOf(f)?.name?.trim()?.lowercase()
                    val key = if (tagged != null && tagged in parts) tagged
                    else centres.maxByOrNull { Embeddings.cosine(it.value, f.emb) }!!.key
                    parts[key]!! += f
                }
                for ((key, list) in parts) {
                    if (list.isEmpty()) continue
                    val display = strong[key]!!.first().second.name!!.trim()
                    named += Cluster(list).also { it.name = display; it.refresh() }
                }
            } else {
                c.name = byName.maxByOrNull { it.value.size }?.value?.first()?.second?.name?.trim()
                c.hidden = tags.count { it.second.hidden } * 2 > tags.size && tags.isNotEmpty()
                named += c
            }
        }
        // One person per name
        val final = ArrayList<Cluster>()
        for (c in named.sortedByDescending { it.faces.size }) {
            val same = c.name?.let { nm -> final.firstOrNull { it.name.equals(nm, ignoreCase = true) } }
            if (same != null) {
                same.faces += c.faces
                same.hidden = same.hidden && c.hidden
                same.refresh()
            } else final += c
        }

        return Result(final.map { c ->
            val cover = c.faces.maxBy { it.q * it.area }
            SearchIndex.NewPerson(
                name = c.name,
                hidden = c.hidden,
                centroid = c.centroid,
                count = c.faces.size,
                coverFace = cover.id,
                coverArea = cover.area,
                faceIds = c.faces.map { it.id },
            )
        })
    }

    private class Cluster(val faces: MutableList<SearchIndex.ClusterFace>) {
        var centroid = FloatArray(0)
        var exemplars: List<FloatArray> = emptyList()
        var name: String? = null
        var hidden = false

        fun refresh() {
            centroid = centroid(faces.map { it.emb })
            exemplars = faces.sortedByDescending { it.q }.take(EXEMPLARS).map { it.emb }
        }

        /** Best of: closeness to the average face, or to any of the clearest faces. */
        fun match(e: FloatArray): Float {
            var best = Embeddings.cosine(e, centroid) + FaceMatch.CENTROID_BONUS
            for (x in exemplars) best = max(best, Embeddings.cosine(e, x))
            return best
        }
    }

    private fun mergeClose(clusters: MutableList<Cluster>) {
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                val a = clusters[i]
                val b = clusters[j]
                if (Embeddings.cosine(a.centroid, b.centroid) < FaceMatch.MERGE_CENTROID) continue
                // Never merge two groups that appear together in the same photo.
                val mediaA = a.faces.mapTo(HashSet()) { it.mediaId }
                if (b.faces.count { it.mediaId in mediaA } > 0) continue
                a.faces += b.faces
                a.refresh()
                clusters.removeAt(j)
                merged = true
                break@loop
            }
        }
    }

    /** For each face, its closest look-alikes above the link threshold (faces in the same photo never link). */
    private suspend fun nearest(flat: FloatArray, n: Int, dim: Int, media: LongArray): Array<List<Pair<Int, Float>>> =
        coroutineScope {
            val threads = max(1, min(6, Runtime.getRuntime().availableProcessors()))
            val chunk = (n + threads - 1) / threads
            val parts = (0 until threads).map { t ->
                async(Dispatchers.Default) {
                    val from = t * chunk
                    val to = min(n, from + chunk)
                    val out = ArrayList<List<Pair<Int, Float>>>(max(0, to - from))
                    val topS = FloatArray(K)
                    val topJ = IntArray(K)
                    for (i in from until to) {
                        topS.fill(-2f); topJ.fill(-1)
                        val oi = i * dim
                        for (j in 0 until n) {
                            if (j == i || media[j] == media[i]) continue
                            val oj = j * dim
                            var s = 0f
                            for (d in 0 until dim) s += flat[oi + d] * flat[oj + d]
                            if (s < FaceMatch.LINK || s <= topS[K - 1]) continue
                            var p = K - 1
                            while (p > 0 && topS[p - 1] < s) { topS[p] = topS[p - 1]; topJ[p] = topJ[p - 1]; p-- }
                            topS[p] = s; topJ[p] = j
                        }
                        val list = ArrayList<Pair<Int, Float>>()
                        for (k in 0 until K) if (topJ[k] >= 0) list += topJ[k] to topS[k]
                        out += list
                    }
                    out
                }
            }
            parts.awaitAll().flatten().toTypedArray()
        }

    private fun centroid(list: List<FloatArray>): FloatArray {
        val sum = FloatArray(list[0].size)
        for (e in list) for (i in sum.indices) sum[i] += e[i]
        return Embeddings.normalize(sum)
    }

    /** Intersection over the smaller box: 1 when one face box sits inside the other. */
    private fun overlap(a: FloatArray, b: FloatArray): Float {
        val w = min(a[2], b[2]) - max(a[0], b[0])
        val h = min(a[3], b[3]) - max(a[1], b[1])
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val small = min((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
        return if (small <= 0f) 0f else inter / small
    }
}
