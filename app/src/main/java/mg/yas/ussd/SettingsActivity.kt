package mg.yas.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    private val fields = mutableMapOf<String, EditText>()

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    private fun saveFields() {
        for ((k, e) in fields) Store.put(this, k, e.text.toString().trim())
    }

    // ───────────────────────── construction ─────────────────────────

    private fun build() {
        saveFields()
        fields.clear()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), dp(40))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.iconButton(this, R.drawable.ic_back) { this@SettingsActivity.finish() })
        val title = Ui.text(this, "Paramètres", 22f, Ui.TEXT, true)
        title.setPadding(dp(14), 0, 0, 0)
        top.addView(title)
        root.addView(top)

        // Ligne et SIM
        root.addView(Ui.section(this, "Ligne"))
        val c1 = Ui.card(this)
        c1.addView(Ui.row(this, "SIM utilisée", Store.get(this, "sim_label", "SIM par défaut")) { pickSim() })
        val on = UssdService.instance != null
        c1.addView(Ui.row(this, "Service d'accessibilité",
            if (on) "Activé ✓" else "Désactivé : requis pour les offres") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        c1.addView(Ui.row(this, "Autorisations", "Appel, téléphone, notifications") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        })
        root.addView(c1)

        // Codes
        root.addView(Ui.section(this, "Codes USSD"))
        val c2 = Ui.card(this)
        c2.addView(field("Code du solde", "k_solde", "#357#", true))
        c2.addView(field("Code des offres", "k_offres", "#359#", true))
        c2.addView(field("Réponse au premier menu (liste des offres)", "k_rep1", "1", false))
        c2.addView(field("Réponse finale (forfait restant)", "k_repfin", "1", false))
        val hint = Ui.text(this, "Pour une offre, l'appli envoie : réponse au premier menu, numéro de l'offre, réponse finale. Les changements sont enregistrés automatiquement.", 12f, Ui.MUTED)
        hint.setPadding(dp(16), dp(12), dp(16), dp(8))
        c2.addView(hint)
        root.addView(c2)

        // Rappel
        root.addView(Ui.section(this, "Rappel de fin de forfait"))
        val c3 = Ui.card(this)
        val t = Store.getLong(this, "reminder")
        val status = if (t > System.currentTimeMillis()) "Prévu le ${fmt.format(Date(t))}" else "Aucun rappel programmé"
        c3.addView(Ui.row(this, "Programmer un rappel", status) { pickReminder() })
        if (t > System.currentTimeMillis()) c3.addView(Ui.row(this, "Annuler le rappel", "") { cancelReminder() })
        root.addView(c3)

        // Avancé
        root.addView(Ui.section(this, "Avancé"))
        val c4 = Ui.card(this)
        c4.addView(Ui.row(this, "Lancer un autre code", "Code libre, avec réponses automatiques facultatives") { freeCode() })
        c4.addView(Ui.row(this, "Journal technique", "Ce que l'appli a lu et envoyé lors des dernières mises à jour") { showJournal() })
        root.addView(c4)

        val ver = Ui.text(this, "Yas USSD 3.0", 12f, Ui.MUTED)
        ver.setPadding(dp(6), dp(24), 0, 0)
        root.addView(ver)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Ui.BG)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(root)
        setContentView(sv)
    }

    private fun field(label: String, key: String, def: String, phone: Boolean): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(12), dp(16), dp(4))
        box.addView(Ui.text(this, label, 13f, Ui.MUTED))
        val e = Ui.input(this, def, Store.cfg(this, key, def), phone)
        fields[key] = e
        box.addView(e, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6) })
        return box
    }

    // ───────────────────────── SIM ─────────────────────────

    @SuppressLint("MissingPermission")
    private fun pickSim() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 2)
            return
        }
        val names = mutableListOf("SIM par défaut")
        val ids = mutableListOf(-1)
        try {
            getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.forEach {
                names.add("SIM ${it.simSlotIndex + 1} – ${it.displayName}")
                ids.add(it.subscriptionId)
            }
        } catch (_: Exception) {}
        val cur = ids.indexOf(Store.subId(this)).coerceAtLeast(0)
        Ui.dialog(this, AlertDialog.Builder(this)
            .setTitle("SIM utilisée")
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, which ->
                Store.setSubId(this, ids[which])
                Store.put(this, "sim_label", names[which])
                d.dismiss()
                build()
            })
    }

    // ───────────────────────── rappel ─────────────────────────

    private fun pendingReminder() = PendingIntent.getBroadcast(
        this, 7, Intent(this, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun pickReminder() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, mi ->
                val cal = Calendar.getInstance()
                cal.set(y, m, d, h, mi, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Choisissez une date future.", Toast.LENGTH_SHORT).show()
                } else {
                    getSystemService(AlarmManager::class.java)
                        .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingReminder())
                    Store.putLong(this, "reminder", cal.timeInMillis)
                    build()
                }
            }, 8, 0, true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun cancelReminder() {
        getSystemService(AlarmManager::class.java).cancel(pendingReminder())
        Store.putLong(this, "reminder", 0L)
        build()
    }

    // ───────────────────────── code libre ─────────────────────────

    private fun freeCode() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(10), dp(22), 0)
        val code = Ui.input(this, "Code (ex : #111#)", "", true)
        val rep = Ui.input(this, "Réponses (ex : 1,2,1), facultatif")
        box.addView(code)
        box.addView(rep, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10) })
        Ui.dialog(this, AlertDialog.Builder(this)
            .setTitle("Lancer un code")
            .setView(box)
            .setPositiveButton("Lancer") { _, _ ->
                val c = code.text.toString().trim()
                if (c.isNotEmpty()) {
                    saveFields()
                    // Le résultat s'affiche sur l'accueil, dans une boîte de dialogue.
                    if (Ussd.start(this, listOf(Ussd.Job(c, c, rep.text.toString().trim())))) {
                        this@SettingsActivity.finish()
                    } else {
                        Toast.makeText(this, "Une mise à jour est déjà en cours.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Annuler", null))
    }

    // ───────────────────────── journal technique ─────────────────────────

    private fun showJournal() {
        val txt = Journal.text(this).ifEmpty { "Journal vide : lancez une mise à jour depuis l'accueil." }
        val tv = Ui.text(this, txt, 12f, Ui.TEXT)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(22), dp(14), dp(22), dp(6))
        val sv = ScrollView(this)
        sv.addView(tv)
        Ui.dialog(this, AlertDialog.Builder(this)
            .setTitle("Journal technique")
            .setView(sv)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Copier") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Journal", txt))
                Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Effacer") { _, _ -> Journal.clear(this) })
    }
}
