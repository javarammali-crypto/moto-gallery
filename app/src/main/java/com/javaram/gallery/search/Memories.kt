package com.javaram.gallery.search

import com.javaram.gallery.data.Media
import com.javaram.gallery.util.toLocalDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Memory(
    val key: String,
    val title: String,
    val subtitle: String,
    val cover: Media,
    val items: List<Media>,
)

/** Builds "On this day", trips, people and highlight stories from the library. */
object Memories {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /** Spreads [n] picks evenly over the list, preferring favourites. */
    private fun pick(list: List<Media>, n: Int): List<Media> {
        if (list.size <= n) return list.sortedBy { it.dateTaken }
        val favs = list.filter { it.isFavorite }.take(n / 3)
        val rest = list.filterNot { it.isFavorite }
        val need = n - favs.size
        val step = rest.size.toFloat() / need
        val spread = (0 until need).mapNotNull { rest.getOrNull((it * step).toInt()) }
        return (favs + spread).distinctBy { it.id }.sortedBy { it.dateTaken }
    }

    private fun range(a: LocalDate, b: LocalDate): String = when {
        a == b -> a.format(dayMonthYear)
        a.year == b.year && a.month == b.month -> "${a.dayOfMonth}–${b.format(dayMonthYear)}"
        a.year == b.year -> "${a.format(dayMonth)} – ${b.format(dayMonthYear)}"
        else -> "${a.format(dayMonthYear)} – ${b.format(dayMonthYear)}"
    }

    fun build(
        all: List<Media>,
        index: Map<Long, IndexEntry>,
        people: Map<Long, Set<String>>,
        today: LocalDate = LocalDate.now(),
    ): List<Memory> {
        val photos = all.filter { !it.bucketName.contains("screenshot", true) && !it.relativePath.contains("screenshot", true) }
        if (photos.isEmpty()) return emptyList()
        val out = ArrayList<Memory>()

        // On this day
        val sameDay = photos.filter {
            val d = it.dateTaken.toLocalDate()
            d.year < today.year && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth
        }
        if (sameDay.size >= 3) {
            val years = sameDay.map { it.dateTaken.toLocalDate().year }.distinct()
            val sub = if (years.size == 1) {
                val ago = today.year - years[0]
                "$ago year${if (ago == 1) "" else "s"} ago"
            } else "From ${years.size} past years"
            val items = pick(sameDay, 40)
            out += Memory("onthisday", "On this day", sub, items.maxBy { if (it.isFavorite) 1 else 0 }, items)
        }

        // Trips: runs of days spent away from the most common place.
        val cityOf = HashMap<Long, String>()
        for (m in photos) index[m.id]?.place?.city?.let { cityOf[m.id] = it }
        val home = cityOf.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (home != null) {
            val byDay = photos.groupBy { it.dateTaken.toLocalDate() }.toSortedMap()
            var start: LocalDate? = null
            var last: LocalDate? = null
            val run = ArrayList<Media>()
            fun flush() {
                val s = start
                val l = last
                if (s != null && l != null && run.size >= 10) {
                    val away = run.mapNotNull { cityOf[it.id] }.filter { it != home }
                    val city = away.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    if (city != null) {
                        val items = pick(run.toList(), 50)
                        out += Memory("trip-$s", "Trip to $city", range(s, l), items[items.size / 2], items)
                    }
                }
                run.clear(); start = null; last = null
            }
            for ((day, list) in byDay) {
                val awayToday = list.any { m -> cityOf[m.id]?.let { it != home } == true }
                val prev = last
                if (awayToday && (prev == null || !day.isAfter(prev.plusDays(1)))) {
                    if (start == null) start = day
                    last = day
                    run += list
                } else {
                    flush()
                    if (awayToday) { start = day; last = day; run += list }
                }
            }
            flush()
        }

        // People
        val byName = HashMap<String, MutableList<Media>>()
        for (m in photos) people[m.id]?.forEach { byName.getOrPut(it) { ArrayList() } += m }
        byName.entries.filter { it.value.size >= 12 }.sortedByDescending { it.value.size }.take(4).forEach { (name, list) ->
            val items = pick(list, 40)
            out += Memory("person-$name", name, "Through the years", items.last(), items)
        }

        // Last 30 days
        val recent = photos.filter { !it.dateTaken.toLocalDate().isBefore(today.minusDays(30)) }
        if (recent.size >= 8) {
            val items = pick(recent, 40)
            out += Memory("recent", "Last 30 days", "Recent highlights", items.last(), items)
        }

        // Last year
        val ly = today.year - 1
        val lastYear = photos.filter { it.dateTaken.toLocalDate().year == ly }
        if (lastYear.size >= 20) {
            val items = pick(lastYear, 60)
            out += Memory("year-$ly", "$ly", "Your year in photos", items[items.size / 2], items)
        }

        // Trips first by recency, then the rest in the order built.
        val trips = out.filter { it.key.startsWith("trip-") }.sortedByDescending { it.key }.take(8)
        return out.filter { !it.key.startsWith("trip-") }.let { rest ->
            (rest.take(1) + trips + rest.drop(1))
        }
    }
}
