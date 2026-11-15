package com.typ02.pomoshnik

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.FileOutputStream
import java.util.Calendar

class Act(val label: String, val run: () -> Unit)

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var input: EditText
    private lateinit var tabTasks: Button
    private lateinit var tabHabits: Button
    private val ui = Handler(Looper.getMainLooper())

    private var tab = 0
    private var reply: String? = null
    private var busy = false
    private var importing: String? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Допомогач"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabTasks = Button(this)
        tabTasks.text = "Справи"
        tabTasks.setOnClickListener { tab = 0; render() }
        tabHabits = Button(this)
        tabHabits.text = "Звички"
        tabHabits.setOnClickListener { tab = 1; render() }
        tabs.addView(tabTasks, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(tabHabits, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs)

        val scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(8), dp(12), dp(16))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(4), dp(8), dp(8))
        input = EditText(this)
        input.hint = "Напишіть справу: «завтра о 8 подзвонити мамі»"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val mic = Button(this)
        mic.text = "🎤"
        mic.setOnClickListener { voice() }
        bar.addView(mic, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        val send = Button(this)
        send.text = "➤"
        send.setOnClickListener { submit() }
        bar.addView(send, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(bar)

        setContentView(root)

        Scheduler.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Scheduler.rescheduleAll(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---------- отрисовка ----------

    private fun render() {
        content.removeAllViews()
        tabTasks.setTypeface(null, if (tab == 0) Typeface.BOLD else Typeface.NORMAL)
        tabHabits.setTypeface(null, if (tab == 1) Typeface.BOLD else Typeface.NORMAL)
        if (tab == 0) renderTasks() else renderHabits()
    }

    private fun text(s: String, size: Float = 15f, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = s
        tv.textSize = size
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        tv.setPadding(0, dp(6), 0, dp(6))
        content.addView(tv)
        return tv
    }

    private fun box(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(0x22888888)
        g.cornerRadius = dp(10).toFloat()
        return g
    }

    private fun card(s: String, vararg acts: Act) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = box()
        c.setPadding(dp(12), dp(10), dp(12), dp(6))
        val tv = TextView(this)
        tv.text = s
        tv.textSize = 15f
        c.addView(tv)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        for (a in acts) {
            val b = Button(this)
            b.text = a.label
            b.setOnClickListener { a.run() }
            row.addView(b)
        }
        c.addView(row)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(4), 0, dp(6))
        content.addView(c, lp)
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        content.addView(b)
        return b
    }

    private fun renderTasks() {
        val now = System.currentTimeMillis()
        if (busy) text("Думаю…")
        val r = reply
        if (r != null) card(r, Act("Сховати") { reply = null; render() })

        for (s in Learner.suggestions(this)) {
            card(
                "💡 " + s.text,
                Act("Ні") { Learner.dismiss(this, s.id); render() },
                Act("Так") {
                    s.apply(this)
                    Learner.dismiss(this, s.id)
                    render()
                },
            )
        }

        val tasks = Db.get(this).active()
        if (tasks.isEmpty()) {
            text(
                "Справ поки немає.\n\nНапишіть або надиктуйте внизу, наприклад:\n" +
                    "• завтра о 8 подзвонити мамі\n" +
                    "• щодня о 21:00 випити таблетки\n" +
                    "• по вівторках і п'ятницях о 19 тренування\n" +
                    "• через 20 хвилин вимкнути плиту\n" +
                    "• купити молоко\n\n" +
                    "Позначайте зроблене кнопкою ✓ — так я вчуся вашим звичкам."
            )
            return
        }
        var lastGroup = ""
        for (t in tasks) {
            val due = t.dueAt
            val group = when {
                due == null -> "Без терміну"
                due < now -> "Прострочено"
                Fmt.daysBetween(now, due) == 0 -> "Сьогодні"
                Fmt.daysBetween(now, due) == 1 -> "Завтра"
                else -> "Пізніше"
            }
            if (group != lastGroup) {
                text(group, 13f, true)
                lastGroup = group
            }
            taskRow(t, now)
        }
    }

    private fun taskRow(t: Task, now: Long) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = box()
        row.setPadding(dp(4), dp(4), dp(10), dp(4))

        val ok = Button(this)
        ok.text = "✓"
        ok.setOnClickListener {
            Logic.complete(this, t)
            Toast.makeText(this, "Готово: " + t.title, Toast.LENGTH_SHORT).show()
            render()
        }
        row.addView(ok, LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(8), 0, 0, 0)
        val title = TextView(this)
        title.text = t.title
        title.textSize = 16f
        col.addView(title)
        val parts = ArrayList<String>()
        val due = t.dueAt
        if (due != null) parts.add(Fmt.whenText(due, now))
        if (t.repeat != "none") parts.add(Recur.label(t.repeat, t.days, due))
        val sn = t.snoozedUntil
        if (sn != null && sn > now) parts.add("відкладено до " + Fmt.hmAt(sn))
        if (parts.isNotEmpty()) {
            val sub = TextView(this)
            sub.text = parts.joinToString(" · ")
            sub.textSize = 13f
            sub.alpha = 0.7f
            col.addView(sub)
        }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { editDialog(t) }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(3), 0, dp(3))
        content.addView(row, lp)
    }

    private fun renderHabits() {
        text("Що я про вас дізнався", 17f, true)
        text(Learner.insights(this))

        val auto = CheckBox(this)
        auto.text = "Самому підлаштовувати час нагадувань під мої звички (без запитання)"
        auto.isChecked = Logic.prefs(this).getBoolean("auto", false)
        auto.setOnCheckedChangeListener { _, v ->
            Logic.prefs(this).edit().putBoolean("auto", v).apply()
        }
        content.addView(auto)

        text("ШІ-модель на телефоні", 17f, true)
        val imp = importing
        if (imp != null) {
            text(imp)
        } else if (Llm.installed(this)) {
            val mb = Llm.file(this).length() / 1_000_000L
            text(
                "Модель підключено ($mb МБ). Тепер можна писати складні фрази й ставити питання " +
                    "(«що в мене на завтра?»). Відповідь займає кілька секунд."
            )
            val err = Llm.lastError
            if (err != null) text("Остання помилка моделі: $err")
            button("Видалити модель") {
                Llm.remove(this)
                ui.postDelayed({ render() }, 500)
            }
        } else {
            text(
                "Без моделі все працює: справи, нагадування, навчання звичкам і розуміння фраз " +
                    "з датою і часом. Модель потрібна для складних фраз і відповідей на питання.\n\n" +
                    "Як підключити (один раз, ~530 МБ):\n" +
                    "1. Відкрийте сторінку моделі, увійдіть на Hugging Face і прийміть ліцензію Gemma.\n" +
                    "2. У вкладці Files завантажте файл ${Llm.MODEL_FILE}.\n" +
                    "3. Поверніться сюди й виберіть завантажений файл."
            )
            button("1. Відкрити сторінку моделі") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Llm.MODEL_PAGE)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Не вдалося відкрити браузер", Toast.LENGTH_SHORT).show()
                }
            }
            button("2. Вибрати завантажений файл") {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "*/*"
                    startActivityForResult(i, REQ_MODEL)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не вдалося відкрити вибір файлу", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------- ввод ----------

    private fun voice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uk-UA")
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажіть, що треба зробити")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            Toast.makeText(this, "Голосове введення на цьому телефоні недоступне", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_VOICE) {
            val said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) {
                input.setText(said)
                submit()
            }
        } else if (requestCode == REQ_MODEL) {
            val uri = data.data
            if (uri != null) importModel(uri)
        }
    }

    private fun importModel(uri: Uri) {
        importing = "Копіюю модель…"
        render()
        Thread {
            var err: String? = null
            val dst = Llm.file(this)
            try {
                val ins = contentResolver.openInputStream(uri) ?: throw Exception("файл не відкривається")
                ins.use { src ->
                    FileOutputStream(dst).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var total = 0L
                        var shown = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total - shown >= 20_000_000L) {
                                shown = total
                                val mb = total / 1_000_000L
                                ui.post {
                                    importing = "Копіюю модель… $mb МБ"
                                    if (tab == 1) render()
                                }
                            }
                        }
                    }
                }
                if (dst.length() < 50_000_000L) {
                    dst.delete()
                    err = "Файл замалий — схоже, це не модель."
                }
            } catch (e: Throwable) {
                dst.delete()
                err = "Не вдалося скопіювати: " + (e.message ?: e.javaClass.simpleName)
            }
            val msg = err
            ui.post {
                importing = null
                if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    private fun isQuestion(s: String): Boolean {
        val l = s.lowercase().trim()
        if (l.endsWith("?")) return true
        return Regex("^(що|які|який|яка|яке|скільки|покажи|розкажи|чи|что|какие|какой|сколько|расскажи)(\\s|$)").containsMatchIn(l)
    }

    private fun looksTimed(s: String): Boolean = Regex(
        "\\d|утр|вечер|вечор|ноч|ніч|обед|обід|ранк|полдень|недел|тиж|месяц|місяц|после|після|пол|пів|" +
            "выходн|вихідн|час|годин|кажд|кожн|щод|щот|щом"
    ).containsMatchIn(s.lowercase())

    private fun submit() {
        val s = input.text.toString().trim()
        if (s.isEmpty() || busy) return
        input.setText("")
        tab = 0
        if (isQuestion(s)) ask(s) else addFromText(s)
    }

    private fun addFromText(s: String) {
        val now = System.currentTimeMillis()
        val p = TimeParser.parse(s, now)
        if (p.empty && Llm.installed(this) && looksTimed(s)) {
            busy = true
            render()
            val prompt = "Зараз " + Fmt.full(now) + ". Перепиши нагадування рівно у два рядки.\n" +
                "СПРАВА: що треба зробити, коротко\n" +
                "КОЛИ: дата і час простими словами, наприклад «завтра о 19:00», «щодня о 8:00», " +
                "«по вівторках о 10:30», «20.11 о 14:00», «через 30 хвилин». Якщо час не вказано, напиши «ні».\n\n" +
                "Нагадування: " + s
            Llm.run(this, prompt) { out ->
                busy = false
                var res = p
                if (out != null) {
                    val what = Regex("СПРАВА\\s*:\\s*(.+)").find(out)?.groupValues?.get(1)?.trim()
                    val whenS = Regex("КОЛИ\\s*:\\s*(.+)").find(out)?.groupValues?.get(1)?.trim(' ', '«', '»', '"', '.')
                    if (!whenS.isNullOrEmpty() && whenS.lowercase() != "ні" && !whenS.lowercase().startsWith("нема")) {
                        val p2 = TimeParser.parse(whenS, now)
                        if (!p2.empty) {
                            val title = if (what.isNullOrEmpty()) "" else TimeParser.cleanTitle(what.trim('«', '»', '"'))
                            p2.title = if (title.isEmpty()) p.title else title
                            res = p2
                        }
                    }
                }
                createTask(res, now)
            }
            return
        }
        createTask(p, now)
    }

    private fun createTask(p: ParseResult, now: Long) {
        val habit = Learner.usual(this, Learner.keyOf(p.title))
        var note = ""
        var due: Long?
        if (p.empty) {
            if (habit != null) {
                due = Learner.nextByHabit(habit, now)
                note = "\nЧас не вказано — поставив як зазвичай: ви робите це " + Learner.habitText(habit) + "."
            } else {
                due = null
            }
        } else {
            val best = Learner.bestHour(this)
            val def = habit?.minute ?: if (best != null) best * 60 else 9 * 60
            due = TimeParser.build(p, now, def)
            if (!p.hasTime) {
                if (due != null && due < now && p.repeat == "none") due = now + 3_600_000L
                note = if (habit != null) "\nЧас узяв із вашої звички."
                else "\nЧас не вказано — поставив сам; натисніть на справу, щоб змінити."
            }
        }
        val t = Task(title = p.title, dueAt = due, repeat = if (due == null) "none" else p.repeat, days = p.days)
        Logic.add(this, t)
        val sb = StringBuilder("Додав: «" + t.title + "»")
        if (due != null) sb.append(" — ").append(Fmt.whenText(due, now)) else sb.append(" — без терміну")
        if (t.repeat != "none") sb.append(", ").append(Recur.label(t.repeat, t.days, due))
        sb.append(".").append(note)
        reply = sb.toString()
        render()
    }

    private fun ask(q: String) {
        if (!Llm.installed(this)) {
            reply = Learner.simpleAnswer(this, q)
            render()
            return
        }
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("Ти особистий помічник. Відповідай коротко, українською і тільки за даними нижче.\n")
        sb.append("Зараз: ").append(Fmt.full(now)).append("\n\nСправи:\n")
        val tasks = Db.get(this).active().take(20)
        if (tasks.isEmpty()) sb.append("(немає)\n")
        for (t in tasks) {
            val due = t.dueAt
            sb.append("- ").append(t.title)
            if (due != null) sb.append(" — ").append(Fmt.whenText(due, now)).append(" (").append(Fmt.date(due)).append(")")
            if (t.repeat != "none") sb.append(", ").append(Recur.label(t.repeat, t.days, due))
            sb.append("\n")
        }
        sb.append("\nСпостереження про звички:\n").append(Learner.insights(this).take(900))
        sb.append("\n\nПитання: ").append(q)
        busy = true
        render()
        Llm.run(this, sb.toString()) { out ->
            busy = false
            reply = out ?: (Learner.simpleAnswer(this, q) + "\n\n(Модель не відповіла: " + (Llm.lastError ?: "немає відповіді") + ")")
            render()
        }
    }

    // ---------- редактирование ----------

    private fun editDialog(t: Task) {
        val now = System.currentTimeMillis()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val title = EditText(this)
        title.setText(t.title)
        title.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        box.addView(title)

        var due: Long? = t.dueAt
        val dateBtn = Button(this)
        val timeBtn = Button(this)
        val clearBtn = Button(this)
        clearBtn.text = "Прибрати термін"
        fun refresh() {
            val d = due
            dateBtn.text = if (d != null) "Дата: " + Fmt.date(d) else "Дата: немає"
            timeBtn.text = if (d != null) "Час: " + Fmt.hmAt(d) else "Час: немає"
        }
        dateBtn.setOnClickListener {
            val c = Recur.cal(due ?: (now + 3_600_000L))
            DatePickerDialog(this, { _, y, m, d ->
                val n = Recur.cal(due ?: Recur.setTime(now, 9 * 60))
                n.set(y, m, d)
                due = n.timeInMillis
                refresh()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        timeBtn.setOnClickListener {
            val c = Recur.cal(due ?: (now + 3_600_000L))
            TimePickerDialog(this, { _, h, m ->
                var n = Recur.setTime(due ?: now, h * 60 + m)
                if (due == null && n < now) n += 86_400_000L
                due = n
                refresh()
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }
        clearBtn.setOnClickListener { due = null; refresh() }
        refresh()
        box.addView(dateBtn)
        box.addView(timeBtn)
        box.addView(clearBtn)

        val codes = arrayListOf("none", "daily", "weekdays", "weekends", "weekly", "monthly")
        val names = arrayListOf(
            "Не повторювати", "Щодня", "По буднях", "На вихідних", "Щотижня", "Щомісяця"
        )
        if (t.repeat == "days") {
            codes.add("days")
            names.add(Recur.label("days", t.days, null).replaceFirstChar { it.uppercase() })
        }
        val spin = Spinner(this)
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spin.adapter = ad
        spin.setSelection(codes.indexOf(t.repeat).coerceAtLeast(0))
        box.addView(spin)

        AlertDialog.Builder(this)
            .setTitle("Справа")
            .setView(box)
            .setPositiveButton("Зберегти") { _, _ ->
                val name = title.text.toString().trim()
                if (name.isNotEmpty()) t.title = name
                val rep = codes[spin.selectedItemPosition.coerceIn(0, codes.size - 1)]
                val d = due
                t.repeat = if (d == null) "none" else rep
                if (t.repeat != "days") t.days = 0
                if (d != t.dueAt) {
                    t.firedAt = null
                    t.snoozedUntil = null
                }
                t.dueAt = if (d != null && t.repeat != "none" && d < System.currentTimeMillis()) {
                    Recur.align(d, t.repeat, t.days, System.currentTimeMillis())
                } else {
                    d
                }
                Logic.update(this, t)
                render()
            }
            .setNeutralButton("Видалити") { _, _ ->
                Logic.delete(this, t)
                render()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    companion object {
        private const val REQ_VOICE = 1
        private const val REQ_MODEL = 2
    }
}
