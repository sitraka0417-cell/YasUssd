package mg.yas.ussd

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Notifications : fin de forfait (date lue dans le résultat) et forfait presque épuisé. */
object Alerts {
    private val dateRe = Regex("""(\d{1,2})[/\-](\d{1,2})[/\-](\d{2,4})(?:\D{0,12}?(\d{1,2})\s*[:hH]\s*(\d{2}))?""")
    private val dataRe = Regex("""(\d+(?:[.,]\d+)?)\s*(Go|GB|Mo|MB)\b""", RegexOption.IGNORE_CASE)
    private val keys = listOf("jusqu", "expire", "valable", "validit", "fin", "avant")
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

    fun notify(c: Context, id: Int, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("forfait", "Forfait", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, "forfait")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Forfait Yas")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    /** Date d'expiration trouvée dans le texte (ex : « jusqu'au 25/11/2026 à 12:30 »), ou null. */
    fun parseExpiry(text: String): Long? {
        val ms = dateRe.findAll(text).toList()
        if (ms.isEmpty()) return null
        val m = ms.firstOrNull { x ->
            val before = text.substring(maxOf(0, x.range.first - 25), x.range.first).lowercase()
            keys.any { before.contains(it) }
        } ?: ms.first()
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        var y = m.groupValues[3].toInt()
        if (y < 100) y += 2000
        if (mo !in 1..12 || d !in 1..31 || y < 2020 || y > 2100) return null
        val hh = (m.groupValues[4].toIntOrNull() ?: 23).coerceIn(0, 23)
        val mi = (m.groupValues[5].toIntOrNull() ?: 59).coerceIn(0, 59)
        val c = Calendar.getInstance()
        c.set(y, mo - 1, d, hh, mi, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun onOffer(c: Context, text: String) {
        val now = System.currentTimeMillis()

        // 1) Fin de forfait : rappel 24 h avant (ou tout de suite si c'est déjà plus proche), une seule fois par échéance.
        val exp = parseExpiry(text)
        if (exp != null) {
            Store.putLong(c, "exp", exp)
            if (exp > now && Store.getLong(c, "exp_set") != exp) {
                val lead = if (exp - now > 48L * 3600_000L) 24L * 3600_000L else 2L * 3600_000L
                val at = maxOf(exp - lead, now + 30_000L)
                val pi = PendingIntent.getBroadcast(c, 8,
                    Intent(c, ReminderReceiver::class.java)
                        .putExtra("id", 2)
                        .putExtra("msg", "Votre forfait expire le ${fmt.format(Date(exp))} : pensez à le renouveler."),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Store.putLong(c, "exp_set", exp)
                Journal.add("Rappel de fin de forfait programmé pour ${fmt.format(Date(at))}")
            }
        }

        // 2) Forfait presque épuisé : premier montant en Go/Mo du texte comparé au seuil choisi.
        val limit = Store.get(c, "low_mb", "200").toIntOrNull() ?: 0
        val m = dataRe.find(text)
        if (limit > 0 && m != null) {
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (v != null) {
                val u = m.groupValues[2].lowercase()
                val mb = if (u == "go" || u == "gb") v * 1024 else v
                if (mb < limit) {
                    if (Store.get(c, "low_sent") != "1") {
                        Store.put(c, "low_sent", "1")
                        notify(c, 3, "Il ne vous reste que ${m.value.trim()} : pensez à recharger.")
                    }
                } else Store.put(c, "low_sent", "0")
            }
        }
    }
}

/** Mise à jour périodique du solde, même appli fermée (silencieuse : aucune fenêtre, aucun écran de chargement). */
object Auto {
    fun minutes(c: Context): Int = Store.get(c, "bg_min", "30").toIntOrNull() ?: 30

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 9,
        Intent(c, RefreshReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val m = minutes(c)
        if (m <= 0) { am.cancel(pi(c)); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + m * 60_000L, pi(c))
    }
}

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Auto.schedule(c)
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        if (Ussd.active) return
        val pr = goAsync()
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val fin = Runnable {
            if (!finished) { finished = true; Ussd.afterAll = null; pr.finish() }
        }
        Ussd.afterAll = { main.removeCallbacksAndMessages(null); fin.run() }
        main.postDelayed(fin, 40_000L)
        Journal.add("Mise à jour périodique du solde")
        if (!Ussd.start(c, listOf(Ussd.soldeJob(c, true)))) fin.run()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Auto.schedule(c) }
}
