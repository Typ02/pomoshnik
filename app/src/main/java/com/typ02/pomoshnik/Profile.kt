package com.typ02.pomoshnik

import android.content.Context
import java.util.Calendar

/** Що означають «вранці», «після роботи» тощо саме для цього користувача (хвилини від півночі). */
class TimeCfg(
    val morning: Int = 480,
    val day: Int = 780,
    val evening: Int = 1140,
    val night: Int = 1320,
    val afterWork: Int = 1110,
    val beforeWork: Int = 480,
    val beforeSleep: Int = 1350,
    val wakeUp: Int = 435,
    val workMask: Int = 31,
)

class Question(val text: String, val options: List<String>)

/** Знайомство з користувачем: хто він, який у нього режим, як йому нагадувати. */
object Me {
    const val DONE = 7
    const val MAX_NAGS = 6
    private const val SKIP = "Пропустити"

    private fun p(ctx: Context) = Logic.prefs(ctx)

    fun step(ctx: Context): Int = p(ctx).getInt("me_step", 0)
    fun known(ctx: Context): Boolean = step(ctx) >= DONE
    fun name(ctx: Context): String = p(ctx).getString("me_name", "") ?: ""
    fun wake(ctx: Context): Int = p(ctx).getInt("me_wake", 420)
    fun sleep(ctx: Context): Int = p(ctx).getInt("me_sleep", 1380)
    fun works(ctx: Context): Boolean = p(ctx).getBoolean("me_works", true)
    fun workMask(ctx: Context): Int = p(ctx).getInt("me_mask", 31)
    fun workStart(ctx: Context): Int = p(ctx).getInt("me_ws", 540)
    fun workEnd(ctx: Context): Int = p(ctx).getInt("me_we", 1080)
    fun nag(ctx: Context): Int = p(ctx).getInt("me_nag", 0)
    private fun gen(ctx: Context): Int = p(ctx).getInt("me_gen", 0)

    fun reset(ctx: Context) {
        p(ctx).edit().putInt("me_step", 0).putInt("me_gen", gen(ctx) + 1).putString("me_starters", "").apply()
    }

    // ---------- питання ----------

    fun question(ctx: Context): Question {
        val n = name(ctx)
        return when (step(ctx)) {
            0 -> Question("Привіт! Я Допомогач. Давайте познайомимось, щоб я підлаштувався під вас.\n\nЯк вас звати?", emptyList())
            1 -> Question(
                (if (n.isNotEmpty()) "Приємно познайомитись, $n!\n\n" else "") + "О котрій ви зазвичай прокидаєтесь?",
                listOf("5:00", "6:00", "7:00", "8:00", "9:00")
            )
            2 -> Question("А о котрій лягаєте спати? Уночі я не турбуватиму.", listOf("21:00", "22:00", "23:00", "0:00", "1:00"))
            3 -> Question("У які дні ви працюєте?", listOf("Пн–Пт", "Пн–Сб", "По-різному", "Не працюю"))
            4 -> Question("О котрій починається і закінчується робота? Наприклад: 9–18", listOf("8–17", "9–18", "10–19"))
            5 -> Question(
                "Якщо ви не відмітили справу як зроблену — нагадувати ще раз?",
                listOf("Кожні 15 хв", "Раз на годину", "Ні, один раз")
            )
            else -> Question(
                "Що вам найважливіше не забувати? Запропоную готові нагадування.",
                listOf("Здоров'я", "Робота", "Дім", "Платежі", "Усе потроху", "Нічого")
            )
        }
    }

    private fun time(s: String, evening: Boolean): Int? {
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?").find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toIntOrNull() ?: 0
        if (h == 24) h = 0
        if (h > 23 || mi > 59) return null
        if (evening && h in 5..11) h += 12
        return h * 60 + mi
    }

    /** Приймає відповідь. Повертає null, якщо все гаразд, або текст із проханням уточнити. */
    fun answer(ctx: Context, raw: String): String? {
        val s = raw.trim()
        val l = s.lowercase()
        val st = step(ctx)
        val e = p(ctx).edit()
        var next = st + 1
        if (s != SKIP) {
            when (st) {
                0 -> {
                    var n = s.replace(Regex("(?i)^(мене звати|меня зовут|мене звуть|моє ім'я|я)\\s+"), "")
                    n = n.trim(' ', '.', ',', '!').take(30).replaceFirstChar { it.uppercase() }
                    e.putString("me_name", n)
                }
                1 -> {
                    val t = time(l, false) ?: return "Не зрозумів час. Напишіть, наприклад: 6:30"
                    e.putInt("me_wake", t)
                }
                2 -> {
                    val t = time(l, true) ?: return "Не зрозумів час. Напишіть, наприклад: 23:00"
                    e.putInt("me_sleep", t)
                }
                3 -> when {
                    l.contains("не працюю") || l.startsWith("не ") || l == "ні" -> {
                        e.putBoolean("me_works", false)
                        next = 5
                    }
                    l.contains("різн") || l.contains("змін") -> {
                        e.putBoolean("me_works", true).putInt("me_mask", 0)
                        next = 5
                    }
                    l.contains("сб") || l.contains("суб") -> e.putBoolean("me_works", true).putInt("me_mask", 63)
                    l.contains("пт") || l.contains("п'ят") || l.contains("будн") ->
                        e.putBoolean("me_works", true).putInt("me_mask", 31)
                    else -> return "Оберіть один із варіантів нижче."
                }
                4 -> {
                    val nums = Regex("(\\d{1,2})(?:[:.](\\d{2}))?").findAll(l).toList()
                    if (nums.size < 2) return "Напишіть початок і кінець, наприклад: 9–18"
                    val a = time(nums[0].value, false)
                    var b = time(nums[1].value, false)
                    if (a == null || b == null) return "Напишіть початок і кінець, наприклад: 9–18"
                    if (b <= a && b < 720) b += 720
                    e.putInt("me_ws", a).putInt("me_we", b.coerceAtMost(1439))
                }
                5 -> when {
                    l.contains("15") -> e.putInt("me_nag", 15)
                    l.contains("30") -> e.putInt("me_nag", 30)
                    l.contains("годин") || l.contains("60") -> e.putInt("me_nag", 60)
                    l.startsWith("ні") || l.contains("один") -> e.putInt("me_nag", 0)
                    l.startsWith("так") -> e.putInt("me_nag", 15)
                    else -> return "Оберіть один із варіантів нижче."
                }
                else -> {
                    val codes = ArrayList<String>()
                    val all = l.contains("усе") || l.contains("все")
                    if (all || l.contains("здоров") || l.contains("лік")) codes.add("health")
                    if (all || l.contains("робот")) codes.add("work")
                    if (all || l.contains("дім") || l.contains("дом") || l.contains("родин")) codes.add("home")
                    if (all || l.contains("плат") || l.contains("комунал")) codes.add("pay")
                    e.putString("me_starters", codes.joinToString(","))
                }
            }
        } else if (st == 3) {
            next = 5
        }
        e.putInt("me_step", next).apply()
        return null
    }

    // ---------- як це використовується ----------

    private fun r5(x: Int): Int = (x + 2) / 5 * 5

    fun cfg(ctx: Context): TimeCfg {
        val w = wake(ctx)
        val s = sleep(ctx)
        val sAdj = if (s <= w) s + 1440 else s
        val works = works(ctx)
        val we = workEnd(ctx)
        val ws = workStart(ctx)
        var evening = if (works) maxOf(we + 60, 18 * 60) else 19 * 60
        evening = minOf(evening, sAdj - 90).coerceIn(15 * 60, 23 * 60)
        val night = minOf(22 * 60, sAdj - 30).coerceAtLeast(evening + 30)
        return TimeCfg(
            morning = r5(w + 30).coerceAtMost(1435),
            day = 13 * 60,
            evening = r5(evening),
            night = r5(night).coerceAtMost(1435),
            afterWork = if (works) r5(we + 30).coerceAtMost(1435) else r5(evening),
            beforeWork = if (works) r5(maxOf(ws - 60, w + 15)).coerceAtMost(1435) else r5(w + 30),
            beforeSleep = r5((sAdj - 30) % 1440),
            wakeUp = r5(w + 15).coerceAtMost(1435),
            workMask = if (works) workMask(ctx) else 31,
        )
    }

    /** Якщо час припадає на сон — переносить на ранок. */
    fun quietShift(ctx: Context, at: Long): Long {
        val w = wake(ctx)
        val s = sleep(ctx)
        val m = Recur.minuteOfDay(at)
        val asleep = if (s > w) (m >= s || m < w) else (m >= s && m < w)
        if (!asleep) return at
        var t = Recur.setTime(at, w)
        if (t <= at) {
            val c = Recur.cal(t)
            c.add(Calendar.DAY_OF_YEAR, 1)
            t = c.timeInMillis
        }
        return t
    }

    fun greeting(ctx: Context, now: Long): String {
        val h = Recur.minuteOfDay(now) / 60
        val hi = when (h) {
            in 5..10 -> "Доброго ранку"
            in 11..16 -> "Добрий день"
            in 17..22 -> "Добрий вечір"
            else -> "Доброї ночі"
        }
        val n = name(ctx)
        return if (n.isNotEmpty()) "$hi, $n!" else "$hi!"
    }

    fun summary(ctx: Context): String {
        val c = cfg(ctx)
        val sb = StringBuilder()
        val n = name(ctx)
        if (n.isNotEmpty()) sb.append("Вас звати $n. ")
        sb.append("Прокидаєтесь о ${Fmt.hm(wake(ctx))}, лягаєте о ${Fmt.hm(sleep(ctx))} — уночі не турбую.\n")
        if (works(ctx)) {
            val m = workMask(ctx)
            if (m == 0) {
                sb.append("Працюєте за змінним графіком.\n")
            } else {
                sb.append("Працюєте ${if (m == 63) "пн–сб" else "пн–пт"}, ${Fmt.hm(workStart(ctx))}–${Fmt.hm(workEnd(ctx))}.\n")
            }
        } else {
            sb.append("Не працюєте за графіком.\n")
        }
        sb.append("Тому для вас: «вранці» — ${Fmt.hm(c.morning)}, ")
        if (works(ctx) && workMask(ctx) != 0) sb.append("«після роботи» — ${Fmt.hm(c.afterWork)}, ")
        sb.append("«ввечері» — ${Fmt.hm(c.evening)}, «перед сном» — ${Fmt.hm(c.beforeSleep)}.\n")
        val g = nag(ctx)
        sb.append(
            if (g > 0) "Якщо справу не відмічено — нагадую повторно кожні $g хв (до $MAX_NAGS разів)."
            else "Нагадую один раз, без повторів."
        )
        return sb.toString()
    }

    // ---------- повторні нагадування ----------

    fun nagN(ctx: Context, id: Long): Int = p(ctx).getInt("nagN_$id", 0)
    fun nagAt(ctx: Context, id: Long): Long = p(ctx).getLong("nagAt_$id", 0L)

    fun bumpNag(ctx: Context, id: Long, now: Long) {
        p(ctx).edit().putInt("nagN_$id", nagN(ctx, id) + 1).putLong("nagAt_$id", now).apply()
    }

    fun clearNag(ctx: Context, id: Long) {
        if (p(ctx).contains("nagN_$id") || p(ctx).contains("nagAt_$id")) {
            p(ctx).edit().remove("nagN_$id").remove("nagAt_$id").apply()
        }
    }

    // ---------- готові нагадування за відповідями ----------

    private fun dropStarter(ctx: Context, code: String) {
        val left = (p(ctx).getString("me_starters", "") ?: "").split(",").filter { it.isNotEmpty() && it != code }
        p(ctx).edit().putString("me_starters", left.joinToString(",")).apply()
    }

    fun starters(ctx: Context): List<Suggestion> {
        if (!known(ctx)) return emptyList()
        val codes = (p(ctx).getString("me_starters", "") ?: "").split(",").filter { it.isNotEmpty() }
        val c = cfg(ctx)
        val g = gen(ctx)
        val out = ArrayList<Suggestion>()
        for (code in codes) {
            when (code) {
                "health" -> out.add(
                    Suggestion("start:$g:health", "Нагадувати щодня о ${Fmt.hm(c.morning)} випити ліки або вітаміни?") { x ->
                        val n = System.currentTimeMillis()
                        val due = Recur.align(Recur.setTime(n, c.morning), "daily", 0, n)
                        Logic.add(x, Task(title = "Випити ліки або вітаміни", dueAt = due, repeat = "daily"))
                        dropStarter(x, "health")
                    }
                )
                "work" -> if (works(ctx)) {
                    val m = (workEnd(ctx) - 15).coerceIn(0, 1435)
                    val mask = workMask(ctx)
                    val rep = if (mask == 31 || mask == 0) "weekdays" else "days"
                    out.add(
                        Suggestion("start:$g:work", "Нагадувати в робочі дні о ${Fmt.hm(m)} скласти план на завтра?") { x ->
                            val n = System.currentTimeMillis()
                            val d = if (rep == "days") mask else 0
                            val due = Recur.align(Recur.setTime(n, m), rep, d, n)
                            Logic.add(x, Task(title = "Скласти план на завтра", dueAt = due, repeat = rep, days = d))
                            dropStarter(x, "work")
                        }
                    )
                }
                "home" -> out.add(
                    Suggestion("start:$g:home", "Нагадувати щосуботи о 10:00 про прибирання?") { x ->
                        val n = System.currentTimeMillis()
                        val due = Recur.align(Recur.setTime(n, 600), "days", 1 shl 5, n)
                        Logic.add(x, Task(title = "Прибирання", dueAt = due, repeat = "weekly"))
                        dropStarter(x, "home")
                    }
                )
                "pay" -> out.add(
                    Suggestion(
                        "start:$g:pay",
                        "Нагадувати 1 числа щомісяця передати показники лічильників і оплатити комунальні?"
                    ) { x ->
                        val n = System.currentTimeMillis()
                        val cal = Recur.cal(Recur.setTime(n, 600))
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        if (cal.timeInMillis <= n) cal.add(Calendar.MONTH, 1)
                        Logic.add(
                            x,
                            Task(
                                title = "Передати показники лічильників і оплатити комунальні",
                                dueAt = cal.timeInMillis, repeat = "monthly"
                            )
                        )
                        dropStarter(x, "pay")
                    }
                )
            }
        }
        return out
    }
}
