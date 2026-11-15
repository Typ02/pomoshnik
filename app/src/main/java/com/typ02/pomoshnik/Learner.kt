package com.typ02.pomoshnik

import android.content.Context
import kotlin.math.abs

/** Привычка: когда пользователь обычно делает дело. */
class Habit(val minute: Int, val dayMask: Int, val count: Int)

/** Предложение помощника, которое можно принять или отклонить. */
class Suggestion(val id: String, val text: String, val apply: (Context) -> Unit)

/**
 * Самообучение. Помощник записывает, когда задачи создаются, когда срабатывает напоминание,
 * когда их реально выполняют и на сколько откладывают, и ищет в этом закономерности.
 */
object Learner {
    private const val DAY = 86_400_000L

    private val STOP = setOf(
        "напомни", "напомнить", "напомните", "мне", "нагадай", "нагадати", "мені", "надо", "нужно",
        "треба", "не", "забыть", "забути", "про", "об", "что", "щоб", "чтобы", "що"
    )

    /** Ключ, по которому одинаковые дела считаются одним и тем же. */
    fun keyOf(title: String): String =
        title.lowercase().replace('ё', 'е')
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in STOP }
            .take(4)
            .joinToString(" ")

    private fun median(xs: List<Long>): Long {
        if (xs.isEmpty()) return 0L
        val s = xs.sorted()
        return s[s.size / 2]
    }

    private fun round5(x: Long): Long = Math.round(x / 5.0) * 5L

    private fun dismissed(ctx: Context): Set<String> =
        Logic.prefs(ctx).getStringSet("dismissed", null) ?: emptySet()

    fun dismiss(ctx: Context, id: String) {
        val set = HashSet(dismissed(ctx))
        set.add(id)
        Logic.prefs(ctx).edit().putStringSet("dismissed", set).apply()
    }

    /** На сколько минут откладывать: столько, через сколько вы обычно реально делаете дело. */
    fun learnedSnooze(ctx: Context): Int {
        val now = System.currentTimeMillis()
        val delays = Db.get(ctx).events(now - 60 * DAY, "done")
            .filter { it.extra > 0 }
            .map { (it.at - it.extra) / 60_000L }
            .filter { it in 3L..180L }
        if (delays.size < 3) return 15
        return round5(median(delays)).toInt().coerceIn(5, 120)
    }

    /** Когда обычно выполняется дело с таким названием. */
    fun usual(ctx: Context, key: String): Habit? {
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        val evs = Db.get(ctx).events(now - 90 * DAY, "done").filter { it.key == key }
        if (evs.size < 2) return null
        val m = round5(median(evs.map { Recur.minuteOfDay(it.at).toLong() })).toInt().coerceIn(0, 1435)
        var mask = 0
        for (e in evs) mask = mask or (1 shl Recur.dayIdxAt(e.at))
        return Habit(m, mask, evs.size)
    }

    fun habitText(h: Habit): String {
        val days = Recur.maskDays(h.dayMask)
        val dayPart = if (days.size <= 2 && h.count >= days.size + 1) {
            "по " + days.joinToString(" і ") { Recur.DN_PL[it] } + " "
        } else ""
        return dayPart + "близько " + Fmt.hm(h.minute)
    }

    /** Ближайший момент, когда по привычке делается это дело. */
    fun nextByHabit(h: Habit, now: Long): Long {
        val start = Recur.setTime(now, h.minute)
        val days = Recur.maskDays(h.dayMask)
        return if (days.size <= 2 && h.count >= days.size + 1) {
            Recur.align(start, "days", h.dayMask, now)
        } else {
            Recur.align(start, "daily", 0, now)
        }
    }

    /** Час, в который вы чаще всего закрываете дела. */
    fun bestHour(ctx: Context): Int? {
        val now = System.currentTimeMillis()
        val evs = Db.get(ctx).events(now - 60 * DAY, "done")
        if (evs.size < 5) return null
        return evs.groupBy { Recur.minuteOfDay(it.at) / 60 }.maxByOrNull { it.value.size }?.key
    }

    /** На сколько минут стоит сдвинуть напоминание повторяющейся задачи (или null). */
    private fun shiftFor(t: Task, evs: List<Ev>): Long? {
        if (t.repeat == "none" || t.dueAt == null) return null
        val mine = evs.filter { it.taskId == t.id }
        val lastAdj = mine.filter { it.type == "adjust" }.maxOfOrNull { it.at } ?: 0L
        val offs = mine
            .filter { it.type == "done" && it.dueAt != null && it.at > lastAdj }
            .sortedBy { it.at }
            .takeLast(7)
            .map { (it.at - (it.dueAt ?: it.at)) / 60_000L }
            .filter { it in -240L..720L }
        if (offs.size < 3) return null
        val med = median(offs)
        if (abs(med) < 20) return null
        val same = offs.count { (it > 0) == (med > 0) && abs(it) >= 10 }
        if (same < offs.size * 0.7) return null
        return round5(med)
    }

    private fun applyShift(ctx: Context, t: Task, mins: Long) {
        val now = System.currentTimeMillis()
        val due = t.dueAt ?: return
        t.dueAt = Recur.align(due + mins * 60_000L, t.repeat, t.days, now)
        t.firedAt = null
        t.snoozedUntil = null
        Logic.update(ctx, t)
        Logic.log(ctx, t, "adjust", mins)
    }

    /** После выполнения: если включена автоподстройка — сдвигаем время сами. */
    fun afterDone(ctx: Context, t: Task) {
        if (!Logic.prefs(ctx).getBoolean("auto", false)) return
        if (t.repeat == "none") return
        val now = System.currentTimeMillis()
        val sh = shiftFor(t, Db.get(ctx).events(now - 90 * DAY)) ?: return
        val old = t.dueAt ?: return
        applyShift(ctx, t, sh)
        val new = t.dueAt ?: return
        Scheduler.info(
            ctx, "Підлаштував час нагадування",
            "«${t.title}»: було ${Fmt.hmAt(old)}, тепер ${Fmt.hmAt(new)} — ви зазвичай робите це саме в цей час."
        )
    }

    fun suggestions(ctx: Context): List<Suggestion> {
        val now = System.currentTimeMillis()
        val db = Db.get(ctx)
        val evs = db.events(now - 90 * DAY)
        val active = db.active()
        val skip = dismissed(ctx)
        val out = ArrayList<Suggestion>()
        for (s in Me.starters(ctx)) if (s.id !in skip) out.add(s)

        // 1. Напоминание стоит не на то время
        for (t in active) {
            val sh = shiftFor(t, evs) ?: continue
            val due = t.dueAt ?: continue
            val id = "shift:${t.id}:$sh"
            if (id in skip) continue
            val how = if (sh > 0) "на $sh хв пізніше" else "на ${-sh} хв раніше"
            val newTime = Fmt.hmAt(due + sh * 60_000L)
            val taskId = t.id
            out.add(
                Suggestion(
                    id,
                    "«${t.title}» ви зазвичай виконуєте $how, ніж приходить нагадування. " +
                        "Перенести нагадування з ${Fmt.hmAt(due)} на $newTime?"
                ) { c ->
                    val fresh = Db.get(c).task(taskId)
                    if (fresh != null) applyShift(c, fresh, sh)
                }
            )
        }

        // 2. Дело повторяется — предложить сделать его регулярным
        val recurringKeys = active.filter { it.repeat != "none" }.map { keyOf(it.title) }.toSet()
        val doneEvs = evs.filter { it.type == "done" && it.key.isNotEmpty() && it.key !in recurringKeys }
        for ((key, list) in doneEvs.groupBy { it.key }) {
            if (list.size < 3) continue
            val dayNums = list.map { Fmt.daysBetween(now, it.at) }.distinct()
            if (dayNums.size < 3) continue
            val span = (dayNums.maxOrNull() ?: 0) - (dayNums.minOrNull() ?: 0)
            var mask = 0
            for (e in list) mask = mask or (1 shl Recur.dayIdxAt(e.at))
            val bits = Integer.bitCount(mask)
            val mod = round5(median(list.map { Recur.minuteOfDay(it.at).toLong() })).toInt().coerceIn(0, 1435)
            val title = list.maxByOrNull { it.at }?.title ?: continue

            val rep: String
            val days: Int
            val label: String
            if (bits <= 2 && span >= 13 && dayNums.size >= bits + 2) {
                rep = "days"
                days = mask
                label = "по " + Recur.maskDays(mask).joinToString(" і ") { Recur.DN_PL[it] }
            } else if (dayNums.size >= 4 && dayNums.size.toDouble() / (span + 1) >= 0.6) {
                if ((mask and 0x60) == 0) {
                    rep = "weekdays"
                    days = 0
                    label = "по буднях"
                } else {
                    rep = "daily"
                    days = 0
                    label = "майже щодня"
                }
            } else {
                continue
            }
            val id = "rec:$key:$rep:$days"
            if (id in skip) continue
            out.add(
                Suggestion(
                    id,
                    "Ви вже ${list.size} раз(и) робили «$title» — $label, зазвичай близько ${Fmt.hm(mod)}. " +
                        "Зробити це регулярною справою, щоб я нагадував сам?"
                ) { c ->
                    val n = System.currentTimeMillis()
                    val due = Recur.align(Recur.setTime(n, mod), rep, days, n)
                    Logic.add(c, Task(title = title, dueAt = due, repeat = rep, days = days))
                }
            )
        }
        return out
    }

    /** Текст «что я о вас знаю». */
    fun insights(ctx: Context): String {
        val now = System.currentTimeMillis()
        val db = Db.get(ctx)
        val evs = db.events(now - 60 * DAY)
        val done = evs.filter { it.type == "done" }
        if (done.size < 3) {
            return "Поки що мало даних. Додавайте справи й позначайте «Готово» — за кілька днів " +
                "я почну помічати, коли ви що робите, і підлаштовуватись."
        }
        val sb = StringBuilder()

        val week = done.filter { it.at >= now - 7 * DAY }
        val withDue = week.filter { it.dueAt != null }
        val onTime = withDue.count { it.at - (it.dueAt ?: it.at) <= 30 * 60_000L }
        sb.append("За 7 днів виконано справ: ${week.size}")
        if (withDue.isNotEmpty()) sb.append(" (вчасно — $onTime із ${withDue.size})")
        sb.append(".\n")

        val missed = evs.count { it.type == "missed" && it.at >= now - 7 * DAY }
        if (missed > 0) sb.append("Пропущено регулярних нагадувань за 7 днів: $missed.\n")

        val hours = done.groupBy { Recur.minuteOfDay(it.at) / 60 }
            .entries.sortedByDescending { it.value.size }.take(2).map { it.key }.sorted()
        if (hours.isNotEmpty()) {
            sb.append("Найчастіше ви закриваєте справи: ")
            sb.append(hours.joinToString(" і ") { Fmt.hm(it * 60) + "–" + Fmt.hm(((it + 1) % 24) * 60) })
            sb.append(".\n")
        }

        val sn = learnedSnooze(ctx)
        val reacted = done.count { it.extra > 0 }
        if (reacted >= 3) {
            sb.append("Після нагадування ви зазвичай робите справу приблизно через $sn хв — ")
            sb.append("тому кнопка «Відкласти» стоїть на $sn хв.\n")
        }

        val snoozes = evs.filter { it.type == "snooze" }.groupBy { it.key }
            .entries.sortedByDescending { it.value.size }.take(3)
        if (snoozes.isNotEmpty()) {
            sb.append("Найчастіше відкладаєте: ")
            sb.append(snoozes.joinToString(", ") {
                "«" + (it.value.lastOrNull()?.title ?: it.key) + "» (" + it.value.size + ")"
            })
            sb.append(".\n")
        }

        val habits = done.filter { it.key.isNotEmpty() }.groupBy { it.key }.filter { it.value.size >= 3 }
        if (habits.isNotEmpty()) {
            sb.append("\nВаші звички:\n")
            for ((key, list) in habits.entries.sortedByDescending { it.value.size }.take(12)) {
                val h = usual(ctx, key) ?: continue
                val title = list.lastOrNull()?.title ?: key
                sb.append("• «$title» — ").append(habitText(h)).append(" (${list.size}×)\n")
            }
        }
        return sb.toString().trim()
    }

    /** Ответ на вопрос без ИИ-модели: просто список дел. */
    fun simpleAnswer(ctx: Context, q: String): String {
        val now = System.currentTimeMillis()
        val s = q.lowercase()
        val all = Db.get(ctx).active()
        val (label, list) = when {
            s.contains("послезавтра") || s.contains("післязавтра") ->
                "Післязавтра" to all.filter { it.dueAt != null && Fmt.daysBetween(now, it.dueAt!!) == 2 }
            s.contains("завтра") ->
                "Завтра" to all.filter { it.dueAt != null && Fmt.daysBetween(now, it.dueAt!!) == 1 }
            s.contains("сегодня") || s.contains("сьогодні") ->
                "Сьогодні" to all.filter { it.dueAt != null && Fmt.daysBetween(now, it.dueAt!!) <= 0 }
            else -> "Найближчі справи" to all.take(7)
        }
        val sb = StringBuilder()
        if (list.isEmpty()) {
            sb.append("$label: справ немає.")
        } else {
            sb.append("$label:\n")
            for (t in list) {
                val due = t.dueAt
                sb.append("• ").append(t.title)
                if (due != null) sb.append(" — ").append(Fmt.whenText(due, now))
                sb.append("\n")
            }
        }
        sb.append("\n\n(Щоб я відповідав на будь-які питання своїми словами, підключіть ШІ-модель у вкладці «Звички».)")
        return sb.toString()
    }
}
