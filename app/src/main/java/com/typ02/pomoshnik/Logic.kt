package com.typ02.pomoshnik

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build

/** Действия над задачами. */
object Logic {
    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("pomoshnik", Context.MODE_PRIVATE)

    fun log(ctx: Context, t: Task, type: String, extra: Long = 0L) {
        Db.get(ctx).log(
            Ev(t.id, Learner.keyOf(t.title), t.title, type, System.currentTimeMillis(), t.dueAt, extra)
        )
    }

    fun add(ctx: Context, t: Task) {
        Db.get(ctx).save(t)
        log(ctx, t, "created")
        Scheduler.schedule(ctx, t)
    }

    fun update(ctx: Context, t: Task) {
        Db.get(ctx).save(t)
        Scheduler.schedule(ctx, t)
    }

    fun delete(ctx: Context, t: Task) {
        Scheduler.cancel(ctx, t.id)
        Db.get(ctx).delete(t.id)
    }

    fun complete(ctx: Context, t: Task) {
        val now = System.currentTimeMillis()
        log(ctx, t, "done", t.firedAt ?: 0L)
        if (t.repeat != "none" && t.dueAt != null) {
            t.dueAt = Recur.next(t, now)
        } else {
            t.done = true
            t.completedAt = now
        }
        t.firedAt = null
        t.snoozedUntil = null
        Db.get(ctx).save(t)
        Scheduler.cancelNotification(ctx, t.id)
        Scheduler.schedule(ctx, t)
        Learner.afterDone(ctx, t)
    }

    fun snooze(ctx: Context, t: Task, minutes: Int) {
        t.snoozedUntil = System.currentTimeMillis() + minutes * 60_000L
        Db.get(ctx).save(t)
        log(ctx, t, "snooze", minutes.toLong())
        Scheduler.cancelNotification(ctx, t.id)
        Scheduler.schedule(ctx, t)
    }
}

/** Будильники и уведомления. */
object Scheduler {
    private const val CH = "reminders"
    const val ACTION_DONE = "com.typ02.pomoshnik.DONE"
    const val ACTION_SNOOZE = "com.typ02.pomoshnik.SNOOZE"
    private const val INFO_ID = 2_000_000_000

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Нагадування", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun alarmPi(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id.toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, t: Task) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmPi(ctx, t.id)
        am.cancel(pi)
        if (t.done) return
        var at = t.snoozedUntil ?: t.dueAt ?: return
        val now = System.currentTimeMillis()
        if (at <= now) {
            val fired = t.firedAt
            if (fired == null || fired < at) {
                at = now + 2000 // ещё не напоминали (например, телефон был выключен)
            } else if (t.repeat != "none") {
                at = Recur.next(t, now) // не отметили — напомним в следующий раз по расписанию
            } else {
                return
            }
        }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmPi(ctx, id))
        cancelNotification(ctx, id)
    }

    fun cancelNotification(ctx: Context, id: Long) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())
    }

    fun rescheduleAll(ctx: Context) {
        for (t in Db.get(ctx).active()) schedule(ctx, t)
    }

    private fun actionPi(ctx: Context, id: Long, action: String, minutes: Int, code: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java)
            .setAction(action)
            .putExtra("id", id)
            .putExtra("min", minutes)
        return PendingIntent.getBroadcast(
            ctx, id.toInt() * 10 + code, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun notify(ctx: Context, t: Task) {
        ensureChannel(ctx)
        val snooze = Learner.learnedSnooze(ctx)
        val second = if (snooze >= 60) 30 else 60
        val icon = Icon.createWithResource(ctx, R.drawable.ic_notif)
        val due = t.dueAt
        val text = (if (due != null) Fmt.hmAt(due) else "") +
            (if (t.repeat != "none") " · " + Recur.label(t.repeat, t.days, due) else "")
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(t.title)
            .setContentText(text.trim(' ', '·'))
            .setContentIntent(openPi(ctx))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setShowWhen(true)
            .addAction(
                Notification.Action.Builder(icon, "Готово", actionPi(ctx, t.id, ACTION_DONE, 0, 1)).build()
            )
            .addAction(
                Notification.Action.Builder(
                    icon, "Через $snooze хв", actionPi(ctx, t.id, ACTION_SNOOZE, snooze, 2)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    icon, if (second == 60) "Через годину" else "Через $second хв",
                    actionPi(ctx, t.id, ACTION_SNOOZE, second, 3)
                ).build()
            )
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(t.id.toInt(), n)
    }

    /** Сообщение от помощника (например, «подстроил время»). */
    fun info(ctx: Context, title: String, text: String) {
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPi(ctx))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(INFO_ID, n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val db = Db.get(ctx)
        val t = db.task(id) ?: return
        if (t.done) return
        val now = System.currentTimeMillis()
        val due = t.dueAt
        val fired = t.firedAt
        // повторяющуюся задачу не отметили — прошлый раз считаем пропущенным
        if (t.repeat != "none" && due != null && fired != null && fired >= due &&
            t.snoozedUntil == null && due < now - 60_000L
        ) {
            Logic.log(ctx, t, "missed")
            t.dueAt = Recur.align(due, t.repeat, t.days, now - 12 * 3_600_000L)
        }
        t.firedAt = now
        t.snoozedUntil = null
        db.save(t)
        Logic.log(ctx, t, "fired")
        Scheduler.notify(ctx, t)
        Scheduler.schedule(ctx, t)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val t = Db.get(ctx).task(id)
        if (t != null) {
            when (intent.action) {
                Scheduler.ACTION_DONE -> Logic.complete(ctx, t)
                Scheduler.ACTION_SNOOZE -> Logic.snooze(ctx, t, intent.getIntExtra("min", 15))
            }
        }
        Scheduler.cancelNotification(ctx, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.ensureChannel(ctx)
        Scheduler.rescheduleAll(ctx)
    }
}
