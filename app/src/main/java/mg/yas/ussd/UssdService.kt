package mg.yas.ussd

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class UssdService : AccessibilityService() {
    companion object { @Volatile var instance: UssdService? = null }

    private val h = Handler(Looper.getMainLooper())
    private val work = Runnable { process() }

    private class Scan {
        val texts = mutableListOf<String>()
        var edit: AccessibilityNodeInfo? = null
        val buttons = mutableListOf<AccessibilityNodeInfo>()
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; removeOverlayNow(); return super.onUnbind(intent) }

    // ── Écran de chargement posé par-dessus la fenêtre USSD du système ──
    private var overlay: View? = null
    private val hideRunnable = Runnable { removeOverlayNow() }

    fun showOverlay() {
        h.removeCallbacks(hideRunnable)
        if (overlay != null) return
        try {
            val d = resources.displayMetrics.density
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.setBackgroundColor(0xFF0D1117.toInt())
            box.addView(ProgressBar(this), LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            val tv = TextView(this)
            tv.text = "Mise à jour en cours…"
            tv.setTextColor(0xFFF1F3F7.toInt())
            tv.textSize = 16f
            tv.setPadding(0, (18 * d).toInt(), 0, 0)
            box.addView(tv)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE)
            getSystemService(WindowManager::class.java).addView(box, lp)
            overlay = box
        } catch (_: Exception) { overlay = null }
    }

    /** Retire l'écran de chargement après un court délai, le temps que la fenêtre USSD se ferme. */
    fun hideOverlay() {
        h.removeCallbacks(hideRunnable)
        h.postDelayed(hideRunnable, 500)
    }

    private fun removeOverlayNow() {
        h.removeCallbacks(hideRunnable)
        val o = overlay ?: return
        overlay = null
        try { getSystemService(WindowManager::class.java).removeView(o) } catch (_: Exception) {}
    }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!Ussd.active) return
        val pkg = e?.packageName?.toString() ?: return
        if (!(pkg.contains("phone") || pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("incall"))) return
        h.removeCallbacks(work)
        h.postDelayed(work, 700)
    }

    private fun walk(n: AccessibilityNodeInfo?, s: Scan) {
        if (n == null) return
        val cls = n.className?.toString() ?: ""
        when {
            cls.endsWith("EditText") -> s.edit = n
            cls.endsWith("Button") -> s.buttons.add(n)
            else -> n.text?.toString()?.let { if (it.isNotBlank()) s.texts.add(it.trim()) }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), s)
    }

    private fun process() {
        if (!Ussd.active) return
        val root = rootInActiveWindow ?: return
        val s = Scan()
        walk(root, s)
        val msg = s.texts.joinToString("\n")
        if (msg.isBlank() && s.buttons.isEmpty()) return

        fun find(vararg w: String) = s.buttons.firstOrNull { b ->
            val t = b.text?.toString()?.lowercase() ?: ""
            w.any { t == it || t.contains(it) }
        }
        val send = find("envoyer", "send", "répondre", "reply", "ok")
        val cancel = find("annuler", "cancel", "fermer", "dismiss", "ok")

        val sig = msg + "|" + Ussd.queue.size
        if (sig == Ussd.lastSig) return
        Ussd.lastSig = sig

        val edit = s.edit
        if (edit != null && Ussd.queue.isNotEmpty()) {
            val r = Ussd.queue.removeFirst()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, r)
            }
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            h.postDelayed({ send?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }, 300)
        } else {
            val b = cancel ?: send
            if (b != null) b.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else performGlobalAction(GLOBAL_ACTION_BACK)
            Ussd.finish(msg)
        }
    }
}
