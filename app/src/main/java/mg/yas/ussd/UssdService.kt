package mg.yas.ussd

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar

/**
 * Pilote la fenêtre USSD du système pendant qu'un écran de chargement (propre à l'appli) la recouvre.
 * Il n'agit que lorsque l'appli a lancé un code (Ussd.capturing) et seulement sur les fenêtres « téléphone ».
 */
class UssdService : AccessibilityService() {
    companion object {
        @Volatile var instance: UssdService? = null
        private val SEND = listOf("envoyer", "send", "répondre", "repondre", "reply", "ok")
        private val CANCEL = listOf("annuler", "cancel", "fermer", "close", "dismiss", "ok")
        private val BTN_WORDS = setOf("ok", "envoyer", "send", "annuler", "cancel", "répondre", "repondre", "reply", "fermer", "close", "dismiss")
        private val PROGRESS = Regex("(?i)(en cours|running|veuillez patienter|please wait|chargement|loading|ex[ée]cution)")
        private const val POLL_MS = 150L
        private const val MAX_ATTEMPTS = 8
    }

    private val h = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!Ussd.capturing) return
            try { step() } catch (e: Exception) { Journal.add("Erreur service : ${e.javaClass.simpleName} ${e.message}") }
            h.postDelayed(this, POLL_MS)
        }
    }
    private val kick = Runnable {
        try { if (Ussd.capturing) step() } catch (e: Exception) { Journal.add("Erreur service : ${e.javaClass.simpleName} ${e.message}") }
    }

    private class Scan {
        val texts = mutableListOf<String>()
        var edit: AccessibilityNodeInfo? = null
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        val pkgs = linkedSetOf<String>()
    }

    // état de la conversation en cours
    private var waiting = false
    private var sentMsg = ""
    private var sentAt = 0L
    private var attempts = 0
    private var emptyPolls = 0
    private var quietMsg = ""
    private var quiet = 0

    override fun onServiceConnected() {
        instance = this
        Journal.add("Service d'accessibilité connecté")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        h.removeCallbacks(poll)
        removeOverlayNow()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        removeOverlayNow()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!Ussd.capturing) return
        h.removeCallbacks(kick)
        h.postDelayed(kick, 60)
    }

    // ───────────────────────── cycle d'un code ─────────────────────────

    /** Début d'un code à menus : on recouvre l'écran puis on surveille la fenêtre USSD. */
    fun begin(title: String, shot: Bitmap? = null) {
        waiting = false
        sentMsg = ""
        attempts = 0
        emptyPolls = 0
        quiet = 0
        quietMsg = ""
        showOverlay(shot)
        h.removeCallbacks(poll)
        h.postDelayed(poll, 250)
    }

    /** Fin du lot : on referme une éventuelle fenêtre restée ouverte puis on retire l'écran de chargement. */
    fun end() {
        h.removeCallbacks(poll)
        h.removeCallbacks(kick)
        dismissNow()
        h.postDelayed({ dismissNow() }, 200)
        scheduleHide(450)
    }

    /** Ferme la fenêtre USSD si elle est encore à l'écran. */
    fun dismissNow() {
        try {
            // Uniquement si un bouton « Annuler / OK / Fermer » est visible : jamais de retour arrière à l'aveugle.
            val s = scan()
            pick(s.buttons, CANCEL)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {}
    }

    // ───────────────────────── lecture de l'écran ─────────────────────────

    private fun isUssdPkg(p: String): Boolean {
        if (p == packageName) return false
        val l = p.lowercase()
        return l.contains("phone") || l.contains("dialer") || l.contains("telecom") ||
            l.contains("incall") || l.contains("telephony") || l.contains("ussd") || l.contains("mmi")
    }

    private fun scan(): Scan {
        val s = Scan()
        var found = false
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val r = w.root ?: continue
                val p = r.packageName?.toString() ?: ""
                if (p.isNotEmpty()) s.pkgs.add(p)
                if (isUssdPkg(p)) { walk(r, s, 0); found = true }
            }
        } catch (_: Exception) {}
        if (!found) {
            val r = rootInActiveWindow
            val p = r?.packageName?.toString() ?: ""
            if (r != null) {
                if (p.isNotEmpty()) s.pkgs.add(p)
                if (isUssdPkg(p)) walk(r, s, 0)
            }
        }
        return s
    }

    private fun walk(n: AccessibilityNodeInfo?, s: Scan, depth: Int) {
        if (n == null || depth > 30) return
        val cls = n.className?.toString() ?: ""
        val t = n.text?.toString()?.trim() ?: ""
        when {
            cls.endsWith("EditText") -> s.edit = n
            cls.endsWith("Button") -> s.buttons.add(n)
            n.isClickable && t.isNotEmpty() && (BTN_WORDS.contains(t.lowercase()) ||
                (!cls.endsWith("TextView") && n.childCount == 0)) -> s.buttons.add(n)
            t.isNotEmpty() -> s.texts.add(t)
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), s, depth + 1)
    }

    private fun pick(list: List<AccessibilityNodeInfo>, words: List<String>): AccessibilityNodeInfo? {
        for (w in words) {
            list.firstOrNull { (it.text?.toString()?.trim()?.lowercase() ?: "") == w }?.let { return it }
        }
        for (w in words) {
            if (w.length < 3) continue
            list.firstOrNull { (it.text?.toString()?.lowercase() ?: "").contains(w) }?.let { return it }
        }
        return null
    }

    // ───────────────────────── une étape ─────────────────────────

    private fun step() {
        if (!Ussd.capturing) return
        val s = scan()
        val msg = s.texts.joinToString("\n").trim()

        if (s.edit == null && s.buttons.isEmpty() && msg.isEmpty()) {
            emptyPolls++
            if (emptyPolls == 8) Journal.add("Aucune fenêtre USSD détectée. Fenêtres vues : ${s.pkgs.joinToString(", ").ifEmpty { "aucune" }}")
            return
        }
        emptyPolls = 0

        // Écran de patience (pas de bouton) : on attend, sauf s'il reste identique un moment (fenêtre sans bouton lisible).
        if (s.edit == null && s.buttons.isEmpty()) {
            if (msg.isEmpty() || PROGRESS.containsMatchIn(msg)) { quiet = 0; return }
            if (msg == quietMsg) quiet++ else { quietMsg = msg; quiet = 1 }
            if (quiet < 3) return
        }

        // Écran déjà traité (la fenêtre n'a pas encore changé après notre réponse).
        if (waiting) {
            if (msg == sentMsg && SystemClock.uptimeMillis() - sentAt < 9000) return
            waiting = false
        }

        val edit = s.edit
        val reply = Ussd.queue.firstOrNull()
        if (edit != null && reply != null) {
            Journal.add("Menu : ${msg.replace("\n", " ⏎ ").take(110)}")
            Ussd.onMenu(msg)
            sendReply(s, edit, reply, msg)
            return
        }

        // Dernier écran : c'est le résultat.
        Journal.add("Résultat lu (${msg.length} car.)")
        closeDialog(s)
        Ussd.done(msg)
    }

    private fun sendReply(s: Scan, edit: AccessibilityNodeInfo, reply: String, msg: String) {
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
        }
        if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            attempts++
            Journal.add("Saisie refusée (essai $attempts)")
            if (attempts >= MAX_ATTEMPTS) Ussd.fail("Impossible de saisir la réponse dans la fenêtre USSD.")
            return
        }
        waiting = true
        sentMsg = msg
        sentAt = SystemClock.uptimeMillis()
        h.postDelayed({
            if (!Ussd.capturing) return@postDelayed
            val b = pick(s.buttons, SEND)
            val clicked = try { b?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false } catch (_: Exception) { false }
            if (clicked) {
                Ussd.queue.removeFirstOrNull()
                attempts = 0
                Journal.add("→ « $reply » envoyé")
            } else {
                waiting = false
                attempts++
                Journal.add("Bouton Envoyer introuvable ou refusé (essai $attempts)")
                if (attempts >= MAX_ATTEMPTS) Ussd.fail("Impossible de valider la réponse dans la fenêtre USSD.")
            }
        }, 120)
    }

    private fun closeDialog(s: Scan) {
        val b = pick(s.buttons, CANCEL)
        val ok = try { b?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false } catch (_: Exception) { false }
        if (!ok) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ───────────────────────── écran de chargement ─────────────────────────

    private var overlay: View? = null
    private val hideRun = Runnable { removeOverlayNow() }
    private val failsafe = Runnable {
        Journal.add("Écran de chargement retiré par sécurité")
        removeOverlayNow()
    }

    private fun scheduleHide(ms: Long) {
        h.removeCallbacks(hideRun)
        h.postDelayed(hideRun, ms)
    }

    private fun showOverlay(shot: Bitmap?) {
        h.removeCallbacks(hideRun)
        h.removeCallbacks(failsafe)
        h.postDelayed(failsafe, 60000)
        if (overlay != null) return
        try {
            val d = resources.displayMetrics.density
            fun px(v: Int) = (v * d).toInt()
            val box = FrameLayout(this)
            box.setBackgroundColor(0xFF0D1117.toInt())

            // Capture de l'accueil, floutée (réduite puis agrandie) : l'écran garde son apparence pendant le chargement.
            if (shot != null) {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.FIT_XY
                iv.setImageBitmap(shot)
                box.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                val dim = View(this)
                dim.setBackgroundColor(0x660D1117)
                box.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }

            val pb = ProgressBar(this)
            pb.indeterminateTintList = ColorStateList.valueOf(0xFFFFC72C.toInt())
            box.addView(pb, FrameLayout.LayoutParams(px(46), px(46), Gravity.CENTER))

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE)
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            getSystemService(WindowManager::class.java).addView(box, lp)
            overlay = box
            Journal.add("Écran de chargement affiché")
        } catch (e: Exception) {
            overlay = null
            Journal.add("Écran de chargement impossible : ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun removeOverlayNow() {
        h.removeCallbacks(hideRun)
        h.removeCallbacks(failsafe)
        val o = overlay ?: return
        overlay = null
        try { getSystemService(WindowManager::class.java).removeView(o) } catch (_: Exception) {}
    }
}
