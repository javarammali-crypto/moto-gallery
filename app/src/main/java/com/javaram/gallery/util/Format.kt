package com.javaram.gallery.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

private val zone: ZoneId get() = ZoneId.systemDefault()

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

private val dayThisYear = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
private val dayOtherYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val fullFmt = DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a", Locale.getDefault())
private val viewerDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
private val viewerTime = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date.year == today.year -> date.format(dayThisYear)
    else -> date.format(dayOtherYear)
}

fun monthLabel(date: LocalDate): String = date.format(monthFmt)

fun fullDateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(fullFmt)

fun viewerTitle(millis: Long): Pair<String, String> {
    val z = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now()
    val d = z.toLocalDate()
    val first = when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> z.format(viewerDate)
    }
    return first to z.format(viewerTime)
}

fun duration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun fileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(4)
    val unit = "KMGT"[exp - 1]
    return String.format(Locale.US, "%.1f %sB", bytes / 1024.0.pow(exp), unit)
}

fun countLabel(n: Int): String = String.format(Locale.getDefault(), "%,d", n)
