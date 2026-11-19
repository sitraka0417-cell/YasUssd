package mg.yas.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** Petit journal technique (consultable dans Paramètres) pour comprendre ce qui s'est passé si un code échoue. */
object Journal {
    private val lines = ArrayDeque<String>()
    private val f = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    fun add(msg: String) {
        synchronized(lines) {
            lines.addLast("${f.format(Date())}  $msg")
            while (lines.size > 200) lines.removeFirst()
        }
    }

    fun text(c: Context): String {
        val mem = synchronized(lines) { lines.joinToString("\n") }
        return if (mem.isNotEmpty()) mem else Store.get(c, "journal")
    }

    fun flush(c: Context) {
        val t = synchronized(lines) { lines.joinToString("\n") }
        Store.put(c, "journal", if (t.length > 12000) t.takeLast(12000) else t)
    }

    fun clear(c: Context) {
        synchronized(lines) { lines.clear() }
        Store.put(c, "journal", "")
    }
}

/**
 * Moteur USSD.
 *
 * Un « lot » est une suite de codes exécutés l'un après l'autre, en arrière-plan :
 *  - sans réponse à envoyer (ex : solde) → requête directe de l'API Android, aucune fenêtre ;
 *  - avec des réponses (ex : offres : 1 → n° → 1) → le code est composé, la fenêtre USSD du système est
 *    masquée par un écran de chargement et pilotée par le service d'accessibilité.
 * Les résultats sont rangés dans Store (r_<clé>, t_<clé>) et l'accueil les affiche.
 */
object Ussd {
    const val MODE_RESULT = 0
    const val MODE_MENU = 1

    /** menuAt : indice de la réponse qui s'envoie sur l'écran de la liste des offres (la liste est alors mémorisée au passage). */
    class Job(val title: String, val code: String, val replies: String, val mode: Int = MODE_RESULT, val key: String = "", val menuAt: Int = -1, val bg: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val jobs = ArrayDeque<Job>()
    private var cur: Job? = null
    private var dialed = false
    private var total = 0

    /** Réponses restant à envoyer pour le code en cours (lu par le service d'accessibilité). */
    val queue = ArrayDeque<String>()

    /** Le moteur est occupé (un lot est en cours). */
    @Volatile var active = false
    /** Une fenêtre USSD du système est attendue : le service d'accessibilité doit la piloter. */
    @Volatile var capturing = false
    @Volatile var runningKey = ""

    /** Appelé une fois à la fin du lot (utilisé par la mise à jour périodique en arrière-plan). */
    var afterAll: (() -> Unit)? = null

    /** Fournit une capture floutée de l'écran affiché (l'accueil) pour l'arrière-plan de l'écran de chargement. */
    var snapshotProvider: (() -> Bitmap?)? = null

    fun soldeJob(c: Context, bg: Boolean = false) =
        Job("Solde", Store.cfg(c, "k_solde", "#357#"), "", MODE_RESULT, "solde", -1, bg)

    /** Appelé (sur le thread principal) à chaque changement : début/fin d'un code, fin du lot. */
    var onChange: (() -> Unit)? = null

    private val timeout = Runnable {
        Journal.add("Délai dépassé")
        finishJob("Délai dépassé : aucune réponse de l'opérateur.", false)
    }

    private val menuLine = Regex("""^\s*(\d{1,2})\s*(?:[.:\-)\]]\s*|\s+)(.+?)\s*$""")
    private val badReply = Regex(
        "(?i)(connection problem|invalid mmi|probl[eè]me de connexion|code mmi|service (indisponible|unavailable)|veuillez r[ée]essayer|please try again)"
    )

    fun isBusy(key: String): Boolean = active && (runningKey == key || jobs.any { it.key == key })

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

    private fun short(t: CharSequence): String {
        val s = t.toString().replace("\n", " ⏎ ")
        return if (s.length > 140) s.take(140) + "…" else s
    }

    // ───────────────────────── lancement ─────────────────────────

    /** Lance un lot de codes. Renvoie false si le moteur est déjà occupé. */
    fun start(ctx: Context, list: List<Job>): Boolean {
        app = ctx.applicationContext
        if (active || list.isEmpty()) return false
        active = true
        dialed = false
        jobs.clear()
        jobs.addAll(list)
        Journal.add("──── Nouveau lot : ${list.joinToString(", ") { it.title }}")
        next()
        return true
    }

    private fun next() {
        val j = jobs.removeFirstOrNull()
        if (j == null) { allDone(); return }
        cur = j
        runningKey = j.key
        queue.clear()
        j.replies.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { queue.addLast(it) }
        total = queue.size
        Journal.add("▶ ${j.title} : ${j.code}  réponses=[${queue.joinToString(",")}]")
        onChange?.invoke()
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            finishJob("Autorisation d'appel manquante (Paramètres > Autorisations).", false)
            return
        }
        val subId = Store.subId(app)
        val pre = preflight(subId)
        if (pre != null) { finishJob(pre, false, false); return }
        if (queue.isEmpty()) single(j, subId) else dial(j, subId)
    }

    /** Vérifie mode avion, SIM et réseau avant de lancer un code : message clair au lieu d'attendre un délai. */
    @SuppressLint("MissingPermission")
    private fun preflight(subId: Int): String? {
        try {
            if (Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
                return "Mode avion activé : désactivez-le pour mettre à jour."
            var tm = app.getSystemService(TelephonyManager::class.java)
            val precise = subId >= 0 || tm.phoneCount <= 1
            if (!precise) return null
            if (subId >= 0) tm = tm.createForSubscriptionId(subId)
            val sim = tm.simState
            if (sim == TelephonyManager.SIM_STATE_ABSENT) return "Aucune SIM détectée."
            if (sim == TelephonyManager.SIM_STATE_PIN_REQUIRED || sim == TelephonyManager.SIM_STATE_PUK_REQUIRED)
                return "SIM verrouillée : saisissez le code PIN."
            val st = tm.serviceState
            if (st != null) {
                if (st.state == ServiceState.STATE_POWER_OFF) return "Radio éteinte : vérifiez le mode avion."
                if (st.state == ServiceState.STATE_OUT_OF_SERVICE) return "Pas de réseau : le téléphone n'est pas connecté à l'opérateur."
                if (st.state == ServiceState.STATE_EMERGENCY_ONLY) return "Réseau limité (appels d'urgence seulement) : pas de signal de l'opérateur."
            }
        } catch (_: Exception) {}
        return null
    }

    private fun armTimeout(ms: Long) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, ms)
    }

    /** Code sans menu : requête directe, aucune fenêtre n'apparaît. */
    @SuppressLint("MissingPermission")
    private fun single(j: Job, subId: Int) {
        armTimeout(30000)
        try {
            var tm = app.getSystemService(TelephonyManager::class.java)
            if (subId >= 0) tm = tm.createForSubscriptionId(subId)
            tm.sendUssdRequest(j.code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(t: TelephonyManager, r: String, resp: CharSequence) {
                    if (cur !== j) return
                    Journal.add("Réponse directe : ${short(resp)}")
                    finishJob(resp.toString(), true)
                }
                override fun onReceiveUssdResponseFailed(t: TelephonyManager, r: String, f: Int) {
                    if (cur !== j) return
                    Journal.add("Réponse directe refusée (code $f) → passage par la fenêtre USSD")
                    dial(j, subId)
                }
            }, main)
        } catch (e: Exception) {
            Journal.add("Requête directe impossible (${e.javaClass.simpleName}) → passage par la fenêtre USSD")
            dial(j, subId)
        }
    }

    /** Code avec menus : on compose le code, le service d'accessibilité répond et lit, un écran de chargement masque tout. */
    @SuppressLint("MissingPermission")
    private fun dial(j: Job, subId: Int) {
        if (j.bg) { finishJob("Réponse directe indisponible en arrière-plan.", false, false); return }
        val svc = UssdService.instance
        if (svc == null) {
            finishJob("Service d'accessibilité désactivé : activez « Yas USSD » dans les services d'accessibilité.", false)
            return
        }
        dialed = true
        capturing = true
        armTimeout(45000)
        val shot = try { snapshotProvider?.invoke() } catch (_: Exception) { null }
        svc.begin(j.title, shot)
        val i = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", j.code, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        phoneAccount(subId)?.let { i.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        try { app.startActivity(i) } catch (e: Exception) {
            finishJob("Impossible de lancer le code : ${e.message}", false)
        }
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

    // ───────────────────────── fin d'un code ─────────────────────────

    /** Appelé par le service avant chaque réponse : mémorise la liste des offres quand c'est l'écran de la liste. */
    fun onMenu(msg: String) {
        val j = cur ?: return
        if (j.menuAt < 0 || total - queue.size != j.menuAt) return
        val items = parseMenu(msg)
        if (items.isEmpty()) return
        Store.saveMenu(app, items)
        Store.put(app, "e_menu", "")
        Journal.add("Liste des offres mémorisée (${items.size})")
        onChange?.invoke()
    }

    /** Appelé par le service d'accessibilité quand le dernier écran USSD a été lu. */
    fun done(text: String) = finishJob(text, true)

    /** Appelé par le service d'accessibilité s'il n'arrive pas à répondre. */
    fun fail(msg: String) = finishJob(msg, false)

    private fun finishJob(textIn: String, okIn: Boolean, countFail: Boolean = true) {
        val j = cur ?: return
        cur = null
        main.removeCallbacks(timeout)
        val wasCapturing = capturing
        capturing = false
        queue.clear()

        val text = textIn.trim()
        var ok = okIn
        var err = text
        if (ok && text.isEmpty()) { ok = false; err = "Réponse vide de l'opérateur." }
        if (ok && badReply.containsMatchIn(text)) { ok = false; err = text }
        val now = System.currentTimeMillis()

        if (j.mode == MODE_MENU) {
            val items = if (ok) parseMenu(text) else emptyList()
            if (items.isNotEmpty()) {
                Store.saveMenu(app, items)
                Store.put(app, "e_menu", "")
            } else {
                Store.put(app, "e_menu", if (ok) "Liste des offres illisible (voir le journal technique)." else err)
                Store.putLong(app, "te_menu", now)
            }
        } else if (j.key.isNotEmpty()) {
            if (ok) {
                Store.put(app, "r_${j.key}", text)
                Store.putLong(app, "t_${j.key}", now)
                Store.put(app, "e_${j.key}", "")
                Store.putLong(app, "f_${j.key}", 0L)
                if (j.key == "offer") try { Alerts.onOffer(app, text) } catch (_: Exception) {}
            } else if (!j.bg) {
                Store.put(app, "e_${j.key}", err)
                Store.putLong(app, "te_${j.key}", now)
                if (countFail) Store.putLong(app, "f_${j.key}", Store.getLong(app, "f_${j.key}") + 1)
            }
        } else {
            // Code libre lancé depuis les paramètres : le résultat s'affiche dans une boîte de dialogue.
            Store.put(app, "last_text", if (ok) text else err)
            Store.put(app, "last_title", j.title)
            Store.putLong(app, "last_time", now)
            Store.put(app, "unseen", "1")
        }

        Journal.add(if (ok) "✔ ${j.title} : ${short(text)}" else "✘ ${j.title} : ${short(err)}")
        // Si le code a échoué alors qu'une fenêtre USSD est restée ouverte, on la ferme.
        if (wasCapturing && !ok) UssdService.instance?.dismissNow()
        onChange?.invoke()
        main.postDelayed({ next() }, 100)
    }

    private fun allDone() {
        active = false
        runningKey = ""
        cur = null
        capturing = false
        Store.putLong(app, "last_end", System.currentTimeMillis())
        Journal.flush(app)
        val svc = UssdService.instance
        val wasDialed = dialed
        dialed = false
        if (wasDialed) {
            try {
                (svc ?: app).startActivity(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) { Journal.add("Retour à l'appli impossible : ${e.message}") }
            svc?.end()
        }
        val cb = afterAll
        afterAll = null
        cb?.invoke()
        onChange?.invoke()
    }
}
