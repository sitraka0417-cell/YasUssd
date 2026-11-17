package mg.yas.ussd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("forfait", "Forfait", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, "forfait")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Forfait Yas")
            .setContentText("Votre forfait arrive à expiration : pensez à le renouveler.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
        Store.putLong(c, "reminder", 0L)
    }
}
