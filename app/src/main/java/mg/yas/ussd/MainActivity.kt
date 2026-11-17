package mg.yas.ussd

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var resultView: TextView
    private lateinit var offersBox: LinearLayout
    private lateinit var accStatus: TextView
    private lateinit var reminderView: TextView
    private lateinit var spinner: Spinner
    private var subIds = listOf(-1)
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply { text = "Yas USSD"; textSize = 26f; setTypeface(null, Typeface.BOLD) })

        accStatus = TextView(this).apply { setPadding(0, dp(8), 0, dp(4)) }
        root.addView(accStatus)
        root.addView(btn("Activer le service d'accessibilité") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(label("SIM à utiliser"))
        spinner = Spinner(this)
        root.addView(spinner)

        root.addView(label("Solde"))
        root.addView(btn("Solde crédit") {
            Ussd.run(this, "Solde crédit", Store.get(this, "code_solde", "#111#"), "")
        })
        root.addView(btn("Solde forfait internet") {
            Ussd.run(this, "Solde forfait internet", Store.get(this, "code_forfait", "#111*1#"), "")
        })
        resultView = TextView(this).apply {
            setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFEEEEEE.toInt()); textSize = 15f
        }
        root.addView(resultView, ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        root.addView(label("Mes offres"))
        offersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(offersBox)
        root.addView(btn("+ Ajouter une offre") { addOffer() })

        root.addView(label("Rappel de fin de forfait"))
        reminderView = TextView(this)
        root.addView(reminderView)
        root.addView(btn("Programmer un rappel") { pickReminder() })
        root.addView(btn("Annuler le rappel") { cancelReminder() })

        root.addView(label("Codes de solde (à vérifier)"))
        val eSolde = EditText(this).apply { hint = "Code solde crédit"; setText(Store.get(this@MainActivity, "code_solde", "#111#")) }
        val eForfait = EditText(this).apply { hint = "Code solde forfait"; setText(Store.get(this@MainActivity, "code_forfait", "#111*1#")) }
        root.addView(eSolde)
        root.addView(eForfait)
        root.addView(btn("Enregistrer les codes") {
            Store.put(this, "code_solde", eSolde.text.toString().trim())
            Store.put(this, "code_forfait", eForfait.text.toString().trim())
            Toast.makeText(this, "Codes enregistrés", Toast.LENGTH_SHORT).show()
        })

        askPermissions()
        loadSims()
        refreshOffers()
    }

    override fun onResume() {
        super.onResume()
        Ussd.onResult = { render() }
        render()
        showReminder()
        accStatus.text = if (UssdService.instance != null) "Accessibilité : activée ✓"
        else "Accessibilité : désactivée (nécessaire pour les offres à plusieurs étapes)"
    }

    override fun onPause() {
        super.onPause()
        Ussd.onResult = null
    }

    private fun render() {
        val t = Store.get(this, "last_text")
        resultView.text = if (t.isEmpty()) "Aucun résultat pour l'instant." else
            "${Store.get(this, "last_title")} — ${fmt.format(Date(Store.getLong(this, "last_time")))}\n\n$t"
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        loadSims()
    }

    private fun loadSims() {
        val names = mutableListOf("Auto (SIM par défaut)")
        val ids = mutableListOf(-1)
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.forEach {
                    names.add("SIM ${it.simSlotIndex + 1} – ${it.displayName}")
                    ids.add(it.subscriptionId)
                }
            } catch (_: Exception) {}
        }
        subIds = ids
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.setSelection(max(0, ids.indexOf(Store.subId(this))))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Store.setSubId(this@MainActivity, subIds[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun refreshOffers() {
        offersBox.removeAllViews()
        val list = Store.offers(this)
        if (list.isEmpty()) offersBox.addView(TextView(this).apply { text = "Aucune offre enregistrée." })
        list.forEachIndexed { idx, o ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = o.name + "\n" + o.code + (if (o.replies.isNotBlank()) "  →  ${o.replies}" else "")
            row.addView(btn(label) { Ussd.run(this, o.name, o.code, o.replies) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("✕") {
                AlertDialog.Builder(this).setTitle("Supprimer « ${o.name} » ?")
                    .setPositiveButton("Supprimer") { _, _ ->
                        val l = Store.offers(this); if (idx < l.size) l.removeAt(idx)
                        Store.saveOffers(this, l); refreshOffers()
                    }.setNegativeButton("Annuler", null).show()
            })
            offersBox.addView(row)
        }
    }

    private fun addOffer() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        val n = EditText(this).apply { hint = "Nom (ex : Forfait 1 Go)" }
        val c = EditText(this).apply { hint = "Code (ex : #111*2#)" }
        val r = EditText(this).apply { hint = "Réponses aux menus (ex : 1,2,1) — facultatif" }
        box.addView(n); box.addView(c); box.addView(r)
        AlertDialog.Builder(this).setTitle("Nouvelle offre").setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val name = n.text.toString().trim(); val code = c.text.toString().trim()
                if (name.isNotEmpty() && code.isNotEmpty()) {
                    val l = Store.offers(this)
                    l.add(Offer(name, code, r.text.toString().trim()))
                    Store.saveOffers(this, l); refreshOffers()
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun pendingReminder() = PendingIntent.getBroadcast(
        this, 7, Intent(this, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun pickReminder() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, mi ->
                val cal = Calendar.getInstance().apply { set(y, m, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Choisissez une date future.", Toast.LENGTH_SHORT).show()
                } else {
                    getSystemService(AlarmManager::class.java)
                        .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingReminder())
                    Store.putLong(this, "reminder", cal.timeInMillis)
                    showReminder()
                }
            }, 8, 0, true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun cancelReminder() {
        getSystemService(AlarmManager::class.java).cancel(pendingReminder())
        Store.putLong(this, "reminder", 0L)
        showReminder()
    }

    private fun showReminder() {
        val t = Store.getLong(this, "reminder")
        reminderView.text = if (t > System.currentTimeMillis()) "Rappel prévu : ${fmt.format(Date(t))}" else "Aucun rappel programmé."
    }
}
