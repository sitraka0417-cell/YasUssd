package mg.yas.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/** Une ligne numérotée d'un menu USSD (ex : « 1 » + « Yas Mi-Net 1 Go »). */
data class MenuItem(val num: String, val label: String)

object Store {
    private fun p(c: Context) = c.getSharedPreferences("yas", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = p(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) = p(c).edit().putString(k, v).apply()
    fun getLong(c: Context, k: String): Long = p(c).getLong(k, 0L)
    fun putLong(c: Context, k: String, v: Long) = p(c).edit().putLong(k, v).apply()
    fun subId(c: Context): Int = p(c).getInt("sub", -1)
    fun setSubId(c: Context, v: Int) = p(c).edit().putInt("sub", v).apply()

    /** Réglage texte : valeur par défaut si rien (ou du vide) n'est enregistré. */
    fun cfg(c: Context, k: String, d: String): String {
        val v = get(c, k, d).trim()
        return if (v.isEmpty()) d else v
    }

    fun menu(c: Context): List<MenuItem> {
        val out = mutableListOf<MenuItem>()
        try {
            val a = JSONArray(get(c, "menu", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(MenuItem(o.getString("n"), o.getString("t")))
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveMenu(c: Context, l: List<MenuItem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.num).put("t", it.label)) }
        put(c, "menu", a.toString())
        putLong(c, "menu_time", System.currentTimeMillis())
    }
}

object Ussd {
    const val MODE_RESULT = 0
    const val MODE_MENU = 1

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    val queue = ArrayDeque<String>()
    @Volatile var active = false
    var lastSig = ""
    var onResult: (() -> Unit)? = null
    private var title = ""
    private var mode = MODE_RESULT
    private var dialed = false
    private val timeout = Runnable { finish("Délai dépassé : aucune réponse de l'opérateur.") }

    private val menuLine = Regex("""^\s*(\d{1,2})\s*(?:[.:\-)\]]\s*|\s+)(.+?)\s*$""")

    /** Extrait les lignes numérotées d'un menu, sans « retour », « suivant », etc. */
    fun parseMenu(text: String): List<MenuItem> {
        val out = mutableListOf<MenuItem>()
        for (line in text.lines()) {
            val m = menuLine.find(line) ?: continue
            val n = m.groupValues[1]
            val t = m.groupValues[2]
            val low = t.lowercase()
            if (n == "0" || n == "00") continue
            if (low.contains("retour") || low.contains("suivant") || low.contains("précédent") || low.contains("precedent")) continue
            out.add(MenuItem(n, t))
        }
        return out
    }

    fun run(ctx: Context, title: String, code: String, replies: String, mode: Int = MODE_RESULT) {
        app = ctx.applicationContext
        this.title = title
        this.mode = mode
        lastSig = ""
        queue.clear()
        replies.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { queue.addLast(it) }
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            finish("Autorisation d'appel manquante. Accordez-la dans Paramètres > Autorisations.")
            return
        }
        val subId = Store.subId(app)
        if (queue.isEmpty()) single(code, subId) else dial(code, subId)
    }

    @SuppressLint("MissingPermission")
    private fun single(code: String, subId: Int) {
        active = true
        dialed = false
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 30000)
        try {
            var tm = app.getSystemService(TelephonyManager::class.java)
            if (subId >= 0) tm = tm.createForSubscriptionId(subId)
            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(t: TelephonyManager, r: String, resp: CharSequence) {
                    finish(resp.toString())
                }
                override fun onReceiveUssdResponseFailed(t: TelephonyManager, r: String, f: Int) {
                    dial(code, subId)
                }
            }, main)
        } catch (e: Exception) {
            dial(code, subId)
        }
    }

    @SuppressLint("MissingPermission")
    private fun dial(code: String, subId: Int) {
        if (UssdService.instance == null) {
            if (queue.isNotEmpty()) {
                finish("Activez d'abord le service d'accessibilité (Paramètres > Service d'accessibilité) pour les offres à plusieurs étapes.")
                return
            }
            active = false
        } else {
            active = true
        }
        dialed = true
        main.removeCallbacks(timeout)
        if (active) main.postDelayed(timeout, 40000)
        val i = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", code, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        phoneAccount(subId)?.let { i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        try { app.startActivity(i) } catch (e: Exception) { finish("Impossible de lancer le code : ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun phoneAccount(subId: Int): PhoneAccountHandle? {
        if (subId < 0) return null
        return try {
            val sm = app.getSystemService(SubscriptionManager::class.java)
            val info = sm.getActiveSubscriptionInfo(subId) ?: return null
            val list = app.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts
            val icc = info.iccId
            list.firstOrNull { !icc.isNullOrEmpty() && it.id.contains(icc) } ?: list.getOrNull(info.simSlotIndex)
        } catch (e: Exception) { null }
    }

    fun finish(text: String) {
        main.removeCallbacks(timeout)
        active = false
        queue.clear()

        // Mode « liste d'offres » : on mémorise le menu ; sinon (ou si rien n'a pu être lu) on affiche le texte brut.
        var show = true
        if (mode == MODE_MENU) {
            val items = parseMenu(text)
            if (items.isNotEmpty()) {
                Store.saveMenu(app, items)
                show = false
            }
        }
        Store.put(app, "last_text", text)
        Store.put(app, "last_title", title)
        Store.putLong(app, "last_time", System.currentTimeMillis())
        Store.put(app, "unseen", if (show) "1" else "0")

        if (dialed) {
            dialed = false
            try {
                app.startActivity(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (_: Exception) {}
        }
        onResult?.invoke()
    }
}
