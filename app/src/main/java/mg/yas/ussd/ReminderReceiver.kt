package mg.yas.ussd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", 1)
        val msg = i.getStringExtra("msg") ?: "Votre forfait arrive à expiration : pensez à le renouveler."
        Alerts.notify(c, id, msg)
        if (id == 1) Store.putLong(c, "reminder", 0L)
    }
}
