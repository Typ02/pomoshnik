package com.typ02.pomoshnik

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Повторы задач. */
object Recur {
    val DN = arrayOf("пн", "вт", "ср", "чт", "пт", "сб", "нд")
    val DN_PL = arrayOf(
        "понеділках", "вівторках", "середах", "четвергах", "п'ятницях", "суботах", "неділях"
    )

    fun cal(at: Long): Calendar = Calendar.getInstance().also { it.timeInMillis = at }

    /** 0 = понедельник … 6 = воскресенье. */
    fun dayIdx(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun dayIdxAt(at: Long): Int = dayIdx(cal(at))

    fun matches(c: Calendar, repeat: String, days: Int): Boolean = when (repeat) {
        "weekdays" -> dayIdx(c) < 5
        "weekends" -> dayIdx(c) >= 5
        "days" -> days == 0 || ((days and (1 shl dayIdx(c))) != 0)
        else -> true
    }

    private fun step(c: Calendar, repeat: String) {
        when (repeat) {
            "weekly" -> c.add(Calendar.DAY_OF_YEAR, 7)
            "monthly" -> c.add(Calendar.MONTH, 1)
            else -> c.add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    /** Первое подходящее время не раньше notBefore, начиная со start. */
    fun align(start: Long, repeat: String, days: Int, notBefore: Long): Long {
        val c = cal(start)
        var guard = 0
        while ((c.timeInMillis < notBefore || !matches(c, repeat, days)) && guard < 2000) {
            step(c, repeat)
            guard++
        }
        return c.timeInMillis
    }

    /** Следующее повторение после текущего срока. */
    fun next(t: Task, now: Long): Long {
        val c = cal(t.dueAt ?: now)
        step(c, t.repeat)
        return align(c.timeInMillis, t.repeat, t.days, now)
    }

    /** Тот же день, но время = minuteOfDay. */
    fun setTime(at: Long, minuteOfDay: Int): Long {
        val c = cal(at)
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        c.set(Calendar.MINUTE, minuteOfDay % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun startOfDay(at: Long): Long = setTime(at, 0)

    fun minuteOfDay(at: Long): Int {
        val c = cal(at)
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun maskDays(mask: Int): List<Int> = (0..6).filter { (mask and (1 shl it)) != 0 }

    fun label(repeat: String, days: Int, dueAt: Long?): String = when (repeat) {
        "daily" -> "щодня"
        "weekdays" -> "по буднях"
        "weekends" -> "на вихідних"
        "weekly" -> "щотижня" + (if (dueAt != null) " (" + DN[dayIdxAt(dueAt)] + ")" else "")
        "monthly" -> "щомісяця"
        "days" -> "по " + maskDays(days).joinToString(", ") { DN[it] }
        else -> ""
    }
}

/** Форматирование дат и времени. */
object Fmt {
    fun hm(minuteOfDay: Int): String =
        String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

    fun hmAt(at: Long): String = hm(Recur.minuteOfDay(at))

    fun date(at: Long): String {
        val c = Recur.cal(at)
        return Recur.DN[Recur.dayIdx(c)] + " " + String.format(
            Locale.US, "%02d.%02d", c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1
        )
    }

    fun full(at: Long): String {
        val c = Recur.cal(at)
        return date(at) + "." + c.get(Calendar.YEAR) + " " + hmAt(at)
    }

    fun daysBetween(from: Long, to: Long): Int =
        ((Recur.startOfDay(to) - Recur.startOfDay(from)) / 86_400_000.0).roundToInt()

    /** «сегодня 08:00», «завтра 19:30», «сб 21.11 10:00». */
    fun whenText(at: Long, now: Long): String {
        val day = when (daysBetween(now, at)) {
            0 -> "сьогодні"
            1 -> "завтра"
            2 -> "післязавтра"
            -1 -> "вчора"
            else -> date(at)
        }
        return day + " " + hmAt(at)
    }
}

/** Результат разбора фразы. */
data class ParseResult(
    var title: String,
    var dateDay: Long?,
    var hour: Int?,
    var minute: Int,
    var repeat: String,
    var days: Int,
    var relative: Long?,
) {
    val hasDate: Boolean get() = dateDay != null || relative != null
    val hasTime: Boolean get() = hour != null || relative != null
    val empty: Boolean get() = !hasDate && !hasTime && repeat == "none"
}

/** Разбор русских и украинских фраз вида «завтра в 8 позвонить маме». Работает без ИИ. */
object TimeParser {
    private const val L = "[а-яёіїєґ'’a-z]"
    private const val B = "(?<![а-яёіїєґ'’a-z0-9])"

    private val WD = listOf(
        listOf("понедельник", "понеділ"),
        listOf("вторник", "вівтор"),
        listOf("сред", "серед"),
        listOf("четвер"),
        listOf("пятниц", "п'ятниц", "п’ятниц"),
        listOf("суббот", "субот"),
        listOf("воскресен", "неділ"),
    )
    private val WD_ALT = WD.flatten().joinToString("|")

    private val MONTHS = listOf(
        listOf("январ", "січ"), listOf("феврал", "лют"), listOf("март", "берез"),
        listOf("апрел", "квіт"), listOf("ма", "трав"), listOf("июн", "черв"),
        listOf("июл", "лип"), listOf("август", "серп"), listOf("сентябр", "верес"),
        listOf("октябр", "жовт"), listOf("ноябр", "листопад"), listOf("декабр", "груд"),
    )
    private const val MON_ALT =
        "январ|феврал|март|апрел|мая|май|июн|июл|август|сентябр|октябр|ноябр|декабр|" +
            "січ|лют|берез|квіт|трав|черв|лип|серп|верес|жовт|листопад|груд"

    private val REPEATS = listOf(
        "daily" to "каждый\\s+день|каждое\\s+утро|каждый\\s+вечер|ежедневно|щодня|щоранку|" +
            "щовечора|кожного\\s+дня|кожен\\s+день|кожного\\s+ранку|кожного\\s+вечора",
        "weekdays" to "по\\s+будням|в\\s+будни|по\\s+будн[яі]х|[ув]\\s+будні|по\\s+рабочим\\s+дням|" +
            "в\\s+рабочие\\s+дни|по\\s+робочих\\s+днях|у\\s+робочі\\s+дні",
        "weekends" to "по\\s+выходным|в\\s+выходные|на\\s+вихідних|по\\s+вихідних|[ув]\\s+вихідні",
        "weekly" to "каждую\\s+неделю|еженедельно|щотижня|кожного\\s+тижня|кожен\\s+тиждень|" +
            "раз\\s+в\\s+неделю|раз\\s+на\\s+тиждень",
        "monthly" to "каждый\\s+месяц|ежемесячно|щомісяця|кожного\\s+місяця|кожен\\s+місяць|" +
            "раз\\s+в\\s+месяц|раз\\s+на\\s+місяць",
    )

    private val PERIODS = listOf(
        8 to "утром|с\\s+утра|зранку|вранці|уранці",
        12 to "в\\s+полдень|опівдні|в\\s+обед|в\\s+обід",
        13 to "днем|вдень|удень",
        19 to "вечером|ввечері|увечері",
        22 to "ночью|вночі|уночі",
    )

    private val EDGE_WORDS = setOf(
        "в", "во", "у", "о", "об", "на", "к", "до", "и", "і", "та", "по", "с", "з", "а", "что", "що"
    )

    private const val PREFIX =
        "^\\s*(?:пожалуйста[,\\s]+|будь\\s+ласка[,\\s]+)?" +
            "(?:напомни(?:те)?|напомнить|нагадай(?:те)?|нагадати|добавь(?:те)?|додай(?:те)?|создай|створи|запиши|поставь)" +
            "(?:\\s+(?:мне|мені|задачу|завдання|напоминание|нагадування))*" +
            "(?:\\s+(?:о|об|про|что|чтобы|що|щоб))?[\\s,:]+"

    private fun wdIndex(word: String): Int = WD.indexOfFirst { stems -> stems.any { word.startsWith(it) } }

    private fun monthIndex(word: String): Int =
        MONTHS.indexOfFirst { stems -> stems.any { word.startsWith(it) } }

    fun parse(input: String, now: Long): ParseResult {
        val s = input.lowercase().replace('ё', 'е')
        val used = BooleanArray(s.length)

        fun free(r: IntRange): Boolean {
            for (i in r) if (used[i]) return false
            return true
        }

        fun take(r: IntRange) {
            for (i in r) used[i] = true
        }

        fun find(pattern: String): List<MatchResult> {
            val out = ArrayList<MatchResult>()
            for (m in Regex(pattern).findAll(s)) if (free(m.range)) out.add(m)
            return out
        }

        var repeat = "none"
        var days = 0
        var date: Calendar? = null
        var hour: Int? = null
        var minute = 0
        var relative: Long? = null
        var period: Int? = null

        // «через 20 минут», «через 2 часа», «через полчаса», «через 3 дня»
        val relM = find(
            "${B}через\\s+(?:(\\d+)\\s*)?(полчаса|пол\\s+часа|півгодини|минут$L*|мин\\.?|хвилин$L*|хв\\.?|" +
                "час$L*|годин$L*|день|дня|дней|дні|днів|недел$L*|тиж$L*)"
        ).firstOrNull()
        if (relM != null) {
            val n = relM.groupValues[1].toIntOrNull() ?: 1
            val u = relM.groupValues[2]
            val mins = when {
                u.startsWith("пол") || u.startsWith("пів") -> 30
                u.startsWith("мин") || u.startsWith("хв") -> n
                u.startsWith("час") || u.startsWith("годин") -> n * 60
                u.startsWith("д") -> n * 1440
                else -> n * 10080
            }
            relative = now + mins * 60_000L
            take(relM.range)
        }

        // повторы
        for ((kind, pat) in REPEATS) {
            val m = find("$B(?:$pat)").firstOrNull() ?: continue
            repeat = kind
            take(m.range)
            val v = m.value
            if (v.contains("утр") || v.contains("ранк")) period = 8
            if (v.contains("вечер") || v.contains("вечор")) period = 19
            break
        }

        // дни недели
        val wdFound = ArrayList<Pair<Int, Int>>() // конец совпадения, индекс дня
        var wdRepeat = false
        for (m in find("$B(по|во|в|у|на|до|к|каждый|каждую|каждое|кожн$L*|що)\\s*($WD_ALT)$L*")) {
            val g = m.groups[2] ?: continue
            val word = s.substring(g.range.first, m.range.last + 1)
            val idx = wdIndex(word)
            if (idx < 0) continue
            if (idx == 2 && (word.startsWith("средн") || word.startsWith("середн") || word.startsWith("средств"))) continue
            val p = m.groupValues[1]
            if (p == "по" || p == "що" || p.startsWith("кажд") || p.startsWith("кожн")) wdRepeat = true
            wdFound.add(m.range.last to idx)
            take(m.range)
        }
        var grew = wdFound.isNotEmpty()
        while (grew) {
            grew = false
            for (m in find("(?:$B(?:и|і|та)\\s+|,\\s*)($WD_ALT)$L*")) {
                val g = m.groups[1] ?: continue
                val word = s.substring(g.range.first, m.range.last + 1)
                val idx = wdIndex(word)
                if (idx < 0) continue
                val near = wdFound.any { m.range.first > it.first && m.range.first <= it.first + 3 }
                if (!near) continue
                wdFound.add(m.range.last to idx)
                take(m.range)
                grew = true
            }
        }
        if (wdFound.isNotEmpty()) {
            if (wdRepeat) {
                repeat = "days"
                for (w in wdFound) days = days or (1 shl w.second)
            } else {
                val c = Recur.cal(now)
                var ahead = (wdFound[0].second - Recur.dayIdx(c) + 7) % 7
                if (ahead == 0) ahead = 7
                c.add(Calendar.DAY_OF_YEAR, ahead)
                date = c
            }
        }

        // сегодня / завтра / послезавтра
        val relDays = listOf(2 to "послезавтра|післязавтра", 1 to "завтра", 0 to "сегодня|сьогодні")
        for ((n, pat) in relDays) {
            val m = find("$B(?:$pat)").firstOrNull() ?: continue
            if (date == null) {
                val c = Recur.cal(now)
                c.add(Calendar.DAY_OF_YEAR, n)
                date = c
            }
            take(m.range)
        }

        // «15 ноября», «3 грудня»
        val monM = find("$B(\\d{1,2})\\s+($MON_ALT)$L*").firstOrNull()
        if (monM != null) {
            val d = monM.groupValues[1].toInt()
            val g = monM.groups[2]
            val word = if (g != null) s.substring(g.range.first, monM.range.last + 1) else ""
            val mi = monthIndex(word)
            if (d in 1..31 && mi >= 0) {
                val c = Recur.cal(now)
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.set(Calendar.MONTH, mi)
                c.set(Calendar.DAY_OF_MONTH, d)
                if (Recur.startOfDay(c.timeInMillis) < Recur.startOfDay(now)) c.add(Calendar.YEAR, 1)
                if (date == null) date = c
                take(monM.range)
            }
        }

        // «5 числа», «5-го числа»
        val numM = find("$B(\\d{1,2})(?:-?(?:го|е|ого))?\\s+числа").firstOrNull()
        if (numM != null) {
            val d = numM.groupValues[1].toInt()
            if (d in 1..31) {
                val c = Recur.cal(now)
                c.set(Calendar.DAY_OF_MONTH, d)
                if (Recur.startOfDay(c.timeInMillis) < Recur.startOfDay(now)) c.add(Calendar.MONTH, 1)
                if (date == null) date = c
                take(numM.range)
            }
        }

        // «в 8», «в 8:30», «о 19.00», «в 7 вечера», «к 9 утра»
        val timeM = find(
            "$B(?:в|во|о|об|к)\\s+(\\d{1,2})(?:[:.](\\d{2}))?(?![0-9])(?:\\s*(?:час$L*|годин$L*|ч\\.))?" +
                "(?:\\s+(утра|ранку|вечера|вечора|дня|ночи|ночі))?"
        ).firstOrNull()
        if (timeM != null) {
            var h = timeM.groupValues[1].toInt()
            val mi = timeM.groupValues[2].toIntOrNull() ?: 0
            val q = timeM.groupValues[3]
            if (h <= 23 && mi <= 59) {
                if ((q.startsWith("вечер") || q.startsWith("вечор") || q == "дня") && h < 12) h += 12
                if ((q.startsWith("ноч") || q == "утра" || q == "ранку") && h == 12) h = 0
                hour = h
                minute = mi
                take(timeM.range)
            }
        }

        // «15.11», «15.11.2026»
        val dmM = find("$B(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?(?![0-9])").firstOrNull()
        if (dmM != null) {
            val d = dmM.groupValues[1].toInt()
            val mo = dmM.groupValues[2].toInt()
            val y = dmM.groupValues[3].toIntOrNull()
            if (d in 1..31 && mo in 1..12) {
                val c = Recur.cal(now)
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.set(Calendar.MONTH, mo - 1)
                c.set(Calendar.DAY_OF_MONTH, d)
                if (y != null) {
                    c.set(Calendar.YEAR, if (y < 100) 2000 + y else y)
                } else if (Recur.startOfDay(c.timeInMillis) < Recur.startOfDay(now)) {
                    c.add(Calendar.YEAR, 1)
                }
                if (date == null) date = c
                take(dmM.range)
            }
        }

        // просто «8:30»
        if (hour == null) {
            val bare = find("$B(\\d{1,2}):(\\d{2})(?![0-9])").firstOrNull()
            if (bare != null) {
                val h = bare.groupValues[1].toInt()
                val mi = bare.groupValues[2].toInt()
                if (h <= 23 && mi <= 59) {
                    hour = h
                    minute = mi
                    take(bare.range)
                }
            }
        }

        // «утром», «вечером», «вдень»
        for ((h, pat) in PERIODS) {
            val m = find("$B(?:$pat)(?!$L)").firstOrNull() ?: continue
            period = h
            take(m.range)
            break
        }
        val hr = hour
        val per = period
        if (hr != null && per != null && hr < 12 && per >= 13) {
            hour = hr + 12
        } else if (hr == null && per != null) {
            hour = per
            minute = 0
        }

        // название = всё, что осталось
        val src = if (input.length == s.length) input else s
        val sb = StringBuilder()
        for (i in s.indices) sb.append(if (used[i]) ' ' else src[i])
        var title = sb.toString()
        val pm = Regex(PREFIX).find(title.lowercase())
        if (pm != null && pm.range.first == 0 && pm.range.last + 1 <= title.length) {
            title = title.substring(pm.range.last + 1)
        }
        title = cleanTitle(title)
        if (title.isEmpty()) title = input.trim()

        val dd = date
        return ParseResult(
            title = title,
            dateDay = if (dd != null) Recur.startOfDay(dd.timeInMillis) else null,
            hour = hour,
            minute = minute,
            repeat = if (relative != null) "none" else repeat,
            days = if (relative != null) 0 else days,
            relative = relative,
        )
    }

    fun cleanTitle(raw: String): String {
        val words = raw.trim().split(Regex("\\s+"))
            .map { it.trim(',', ';', ':') }
            .filter { it.isNotEmpty() }
            .toMutableList()
        while (words.isNotEmpty() && words.last().lowercase() in EDGE_WORDS) words.removeAt(words.size - 1)
        while (words.isNotEmpty() && words.first().lowercase() in EDGE_WORDS) words.removeAt(0)
        val t = words.joinToString(" ").trim(' ', ',', '.', ';', ':', '-', '–', '—', '!')
        return t.replaceFirstChar { it.uppercase() }
    }

    /** Вычисляет срок. defaultMinute — время дня, если пользователь назвал только дату. */
    fun build(p: ParseResult, now: Long, defaultMinute: Int): Long? {
        val rel = p.relative
        if (rel != null) return rel
        val h = p.hour
        val day = p.dateDay
        if (day == null && h == null && p.repeat == "none") return null
        val mod = if (h != null) h * 60 + p.minute else defaultMinute
        var due = Recur.setTime(day ?: now, mod)
        if (p.repeat != "none") {
            due = Recur.align(due, p.repeat, p.days, now)
        } else if (day == null && due < now) {
            val c = Recur.cal(due)
            c.add(Calendar.DAY_OF_YEAR, 1)
            due = c.timeInMillis
        }
        return due
    }
}
