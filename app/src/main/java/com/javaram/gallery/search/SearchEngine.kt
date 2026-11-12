package com.javaram.gallery.search

import com.javaram.gallery.data.Media
import com.javaram.gallery.util.toLocalDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class Suggestion(
    val title: String,
    val subtitle: String,
    val cover: Media?,
    val query: String,
)

data class PlaceCluster(
    val name: String,
    val lat: Double,
    val lng: Double,
    val count: Int,
    val cover: Media,
)

/** Turns a typed query into matching photos using dates, places, things, text and names. */
object SearchEngine {

    private val months = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9, "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11, "dec" to 12, "december" to 12,
    )

    private class DateFilter(val test: (LocalDate) -> Boolean, val consumed: Set<Int>)

    private fun parseDate(tokens: List<String>, today: LocalDate = LocalDate.now()): DateFilter? {
        val joined = tokens.joinToString(" ")
        fun idx(vararg words: String): Set<Int> =
            tokens.withIndex().filter { it.value in words }.map { it.index }.toSet()

        when {
            "today" in tokens -> return DateFilter({ it == today }, idx("today"))
            "yesterday" in tokens -> return DateFilter({ it == today.minusDays(1) }, idx("yesterday"))
            joined.contains("this week") || joined.contains("last week") -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .minusWeeks(if (joined.contains("last week")) 1 else 0)
                return DateFilter({ !it.isBefore(start) && it.isBefore(start.plusWeeks(1)) }, idx("this", "last", "week"))
            }
            joined.contains("this month") || joined.contains("last month") -> {
                val ym = YearMonth.from(today).minusMonths(if (joined.contains("last month")) 1 else 0)
                return DateFilter({ YearMonth.from(it) == ym }, idx("this", "last", "month"))
            }
            joined.contains("this year") || joined.contains("last year") -> {
                val y = today.year - if (joined.contains("last year")) 1 else 0
                return DateFilter({ it.year == y }, idx("this", "last", "year"))
            }
        }

        var month: Int? = null
        var year: Int? = null
        var day: Int? = null
        val used = HashSet<Int>()
        tokens.forEachIndexed { i, t ->
            val clean = t.trimEnd(',', '.')
            when {
                months.containsKey(clean) && month == null -> { month = months[clean]; used += i }
                clean.length == 4 && clean.toIntOrNull()?.let { it in 1990..2100 } == true && year == null -> {
                    year = clean.toInt(); used += i
                }
            }
        }
        if (month != null) {
            // A day number right next to the month: "12 march" or "march 12".
            tokens.forEachIndexed { i, t ->
                val n = t.trimEnd(',', '.').removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").toIntOrNull()
                if (n != null && n in 1..31 && i !in used && day == null) {
                    val near = used.any { kotlin.math.abs(it - i) == 1 }
                    if (near) { day = n; used += i }
                }
            }
        }
        if (month == null && year == null) return null
        val m = month
        val y = year
        val d = day
        return DateFilter({ date ->
            (m == null || date.monthValue == m) && (y == null || date.year == y) && (d == null || date.dayOfMonth == d)
        }, used)
    }

    private fun singular(t: String): String = when {
        t.length > 4 && t.endsWith("ies") -> t.dropLast(3) + "y"
        t.length > 3 && t.endsWith("es") && (t.endsWith("ses") || t.endsWith("ches") || t.endsWith("shes")) -> t.dropLast(2)
        t.length > 3 && t.endsWith("s") && !t.endsWith("ss") -> t.dropLast(1)
        else -> t
    }

    fun search(
        query: String,
        media: List<Media>,
        index: Map<Long, IndexEntry>,
        people: Map<Long, Set<String>> = emptyMap(),
    ): List<Media> {
        val tokens = query.lowercase(Locale.getDefault())
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val date = parseDate(tokens)
        val rest = tokens.filterIndexed { i, _ -> date == null || i !in date.consumed }
            .filter { it !in setOf("in", "at", "of", "from", "on", "the", "my", "and", "with") }

        var onlyVideo = false
        var onlyPhoto = false
        var onlyFav = false
        var screenshots = false
        val words = ArrayList<String>()
        for (t in rest) {
            when (singular(t)) {
                "video", "clip", "movie" -> onlyVideo = true
                "photo", "picture", "pic", "image" -> onlyPhoto = true
                "favourite", "favorite", "fav", "liked" -> onlyFav = true
                "screenshot" -> screenshots = true
                else -> words += t
            }
        }

        return media.filter { m ->
            if (onlyVideo && !m.isVideo) return@filter false
            if (onlyPhoto && m.isVideo) return@filter false
            if (onlyFav && !m.isFavorite) return@filter false
            if (screenshots && !m.bucketName.contains("screenshot", true) && !m.relativePath.contains("screenshot", true)) {
                return@filter false
            }
            if (date != null && !date.test(m.dateTaken.toLocalDate())) return@filter false
            if (words.isEmpty()) return@filter true
            val e = index[m.id]
            val names = people[m.id]
            if (names != null) {
                // A person's full name may span several words ("Riya Shah").
                val joined = words.joinToString(" ")
                if (names.any { it.equals(joined, true) || it.contains(joined, true) }) return@filter true
            }
            words.all { w -> matches(w, m, e) || names?.any { it.contains(w, true) } == true }
        }
    }

    private fun matches(word: String, m: Media, e: IndexEntry?): Boolean {
        val s = singular(word)
        if (m.bucketName.contains(word, true)) return true
        if (m.name.contains(word, true)) return true
        if (e == null) return false
        if (e.labels.any { l -> val ll = l.lowercase(); ll.startsWith(s) || ll == word || singular(ll) == s }) return true
        val p = e.place
        if (p != null && listOfNotNull(p.area, p.city, p.state, p.country).any { it.contains(word, true) }) return true
        if (word.length >= 3 && e.text.contains(word, true)) return true
        return false
    }

    fun places(media: List<Media>, index: Map<Long, IndexEntry>): List<PlaceCluster> {
        val groups = HashMap<String, MutableList<Pair<Media, IndexEntry>>>()
        for (m in media) {
            val e = index[m.id] ?: continue
            val name = e.place?.city ?: e.place?.state ?: continue
            if (e.lat == null || e.lng == null) continue
            groups.getOrPut(name) { ArrayList() }.add(m to e)
        }
        return groups.map { (name, list) ->
            PlaceCluster(
                name = name,
                lat = list.map { it.second.lat!! }.average(),
                lng = list.map { it.second.lng!! }.average(),
                count = list.size,
                cover = list.maxBy { it.first.dateTaken }.first,
            )
        }.sortedByDescending { it.count }
    }

    fun placeSuggestions(media: List<Media>, index: Map<Long, IndexEntry>): List<Suggestion> =
        places(media, index).take(20).map { Suggestion(it.name, "${it.count}", it.cover, it.name) }

    fun thingSuggestions(media: List<Media>, index: Map<Long, IndexEntry>): List<Suggestion> {
        val counts = HashMap<String, Int>()
        val covers = HashMap<String, Media>()
        for (m in media) {
            val e = index[m.id] ?: continue
            for (l in e.labels.take(3)) {
                counts[l] = (counts[l] ?: 0) + 1
                if (!covers.containsKey(l)) covers[l] = m
            }
        }
        return counts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(24)
            .map { Suggestion(it.key, "${it.value}", covers[it.key], it.key.lowercase()) }
    }

    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    fun monthSuggestions(media: List<Media>): List<Suggestion> {
        val groups = LinkedHashMap<YearMonth, MutableList<Media>>()
        for (m in media) groups.getOrPut(YearMonth.from(m.dateTaken.toLocalDate())) { ArrayList() }.add(m)
        return groups.entries.take(18).map { (ym, list) ->
            Suggestion(ym.format(monthFmt), "${list.size}", list.first(), ym.format(monthFmt).lowercase())
        }
    }

    fun textSuggestionsAvailable(index: Map<Long, IndexEntry>): Int = index.values.count { it.text.length > 20 }
}
