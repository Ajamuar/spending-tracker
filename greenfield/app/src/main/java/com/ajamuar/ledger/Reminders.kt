package com.ajamuar.ledger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.YearMonth

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences("ledger", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("reminders", false)) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        return try {
            val repo = (applicationContext as LedgerApplication).repository
            val today = LocalDate.now(India)
            val due = repo.dao.billSnapshot().filter { !it.paid && !LocalDate.parse(it.due).isAfter(today.plusDays(3)) }
            val month = YearMonth.from(today)
            val entries = repo.dao.snapshot().filter { YearMonth.from(it.date()) == month }
            val budgets = repo.dao.budgetSnapshot().filter { b -> entries.filter { it.category == b.category }.sumOf { it.personalSpend() } >= b.limitMinor }
            val keys = due.map { "bill:${it.id}:${it.due}" } + budgets.map { "budget:$month:${it.category}:${it.limitMinor}" }
            val fresh = keys.filterNot { preferences.getBoolean(it, false) }
            if (fresh.isNotEmpty()) {
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("ledger-reminders", "Private money reminders", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val notification = NotificationCompat.Builder(applicationContext, "ledger-reminders")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Your money check-in")
                    .setContentText("A bill is due soon or a budget needs attention. Open your ledger to review.")
                    .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_SECRET).build()
                manager.notify(1, notification)
                preferences.edit().apply { fresh.forEach { putBoolean(it, true) } }.apply()
            }
            Result.success()
        } catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
