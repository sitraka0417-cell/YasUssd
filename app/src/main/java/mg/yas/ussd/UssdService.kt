package mg.yas.ussd

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
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
            Ussd.finish(msg)
            val b = cancel ?: send
            if (b != null) b.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }
}
