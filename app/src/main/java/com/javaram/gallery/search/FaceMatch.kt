package com.javaram.gallery.search

import kotlin.math.max

/**
 * Decides which faces belong together.
 *
 * Calibrated on this FaceNet model: clean photos of the same person score ≥ 0.8, different
 * people stay ≤ ~0.35. Everyday photos (angles, lighting, kids growing up) pull same-person
 * scores down, so a face joins a person if it is close to their average face OR close to any
 * one of their clearest faces.
 */
object FaceMatch {
    const val CENTROID = 0.50f
    const val EXEMPLAR = 0.60f
    const val MERGE_CENTROID = 0.56f
    const val MERGE_LINK = 0.62f
    const val SUGGEST = 0.42f
    const val MAX_EXEMPLARS = 24

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
