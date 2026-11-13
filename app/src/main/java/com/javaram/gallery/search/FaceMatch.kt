package com.javaram.gallery.search

import kotlin.math.max

/**
 * Decides which faces belong together.
 *
 * Calibrated on the ArcFace (InsightFace MobileFaceNet) model on labelled photo pairs:
 * the same person scores 0.42 to 0.9 (typically ~0.73), different people stay below ~0.24.
 */
object FaceMatch {
    /** Two clear faces this similar are linked when grouping the whole library. */
    const val LINK = 0.40f
    /** A clear new face joins a person at this similarity. */
    const val ASSIGN_CLEAR = 0.42f
    /** A blurry, small or turned face needs a stronger match. */
    const val ASSIGN_WEAK = 0.48f
    /** Added to centroid similarity: an average face scores lower than a single good match. */
    const val CENTROID_BONUS = 0.06f
    const val CENTROID = 0.36f
    const val EXEMPLAR = 0.42f
    const val MERGE_CENTROID = 0.50f
    const val MERGE_LINK = 0.55f
    const val SUGGEST = 0.30f
    const val MAX_EXEMPLARS = 40

    class Group(
        val id: Long,
        var centroid: FloatArray,
        var count: Int,
        val exemplars: MutableList<FloatArray>,
        var name: String?,
    )

    /** How well a face fits a group, or null if it doesn't fit. */
    fun score(e: FloatArray, g: Group): Float? {
        val cs = Embeddings.cosine(e, g.centroid)
        var ex = -1f
        for (x in g.exemplars) { val s = Embeddings.cosine(e, x); if (s > ex) ex = s }
        return if (cs >= CENTROID || ex >= EXEMPLAR) max(cs + 0.1f, ex) else null
    }

    fun best(e: FloatArray, groups: List<Group>): Group? {
        var best: Group? = null
        var bestScore = -1f
        for (g in groups) {
            val s = score(e, g) ?: continue
            if (s > bestScore) { bestScore = s; best = g }
        }
        return best
    }

    fun add(g: Group, e: FloatArray) {
        g.centroid = Embeddings.blend(g.centroid, g.count, e, 1)
        g.count += 1
        if (g.exemplars.size < MAX_EXEMPLARS) g.exemplars += e
    }

    /** Average of the three strongest face-to-face matches between two groups. */
    fun link(a: Group, b: Group): Float {
        val top = FloatArray(3) { -1f }
        for (x in a.exemplars) for (y in b.exemplars) {
            val s = Embeddings.cosine(x, y)
            if (s > top[2]) {
                top[2] = s
                top.sortDescending()
            }
        }
        val valid = top.filter { it > -1f }
        return if (valid.isEmpty()) -1f else valid.average().toFloat()
    }

    /** Similarity used to decide merging; cheap centroid check first. */
    fun closeness(a: Group, b: Group): Float {
        val cs = Embeddings.cosine(a.centroid, b.centroid)
        if (cs < SUGGEST - 0.08f) return cs
        return max(cs, link(a, b) - (MERGE_LINK - MERGE_CENTROID))
    }

    fun shouldMerge(a: Group, b: Group): Boolean {
        if (a.name != null && b.name != null && !a.name.equals(b.name, true)) return false
        return closeness(a, b) >= MERGE_CENTROID
    }

    fun absorb(into: Group, from: Group) {
        into.centroid = Embeddings.blend(into.centroid, into.count, from.centroid, from.count)
        into.count += from.count
        for (x in from.exemplars) if (into.exemplars.size < MAX_EXEMPLARS) into.exemplars += x
        if (into.name == null) into.name = from.name
    }
}
