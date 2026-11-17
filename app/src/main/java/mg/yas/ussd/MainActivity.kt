package mg.yas.ussd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private enum class Page { HOME, OFFERS }

    private lateinit var frame: FrameLayout
    private var page = Page.HOME
    private var loading: AlertDialog? = null
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

    private fun dp(v: Int) = Ui.dp(this, v)

    // ───────────────────────── cycle de vie ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        frame.setBackgroundColor(Ui.BG)
        setContentView(frame)
        askPermissions()
        show(Page.HOME)
    }

    override fun onResume() {
        super.onResume()
        Ussd.onResult = { onUssdDone() }
        show(page)
        if (!Ussd.active) dismissLoading()
        handleUnseen()
    }

    override fun onPause() {
        super.onPause()
        Ussd.onResult = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page == Page.OFFERS) show(Page.HOME) else super.onBackPressed()
    }

    private fun show(p: Page) {
        page = p
        frame.removeAllViews()
        val v = if (p == Page.HOME) buildHome() else buildOffers()
        frame.addView(v, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
    }

    // ───────────────────────── accueil ─────────────────────────

    private fun buildHome(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), dp(24))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(Ui.text(this, "Yas USSD", 26f, Ui.TEXT, true))
        titles.addView(Ui.text(this, Store.get(this, "sim_label", "SIM par défaut"), 13f, Ui.MUTED))
        top.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(top)

        if (UssdService.instance == null) {
            val banner = Ui.text(this, "Accessibilité désactivée : touchez ici pour l'activer (nécessaire pour les offres).", 13f, Ui.ACCENT)
            banner.setPadding(dp(16), dp(12), dp(16), dp(12))
            banner.background = Ui.ripple(this, Ui.SURFACE, 14)
            banner.isClickable = true
            banner.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            root.addView(banner, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(16) })
        }

        val solde = bigCard(R.drawable.ic_wallet, "Solde", "Voir mon crédit", true) { runSolde() }
        val offres = bigCard(R.drawable.ic_list, "Offres", "Forfait restant", false) { openOffers() }
        root.addView(solde, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = dp(24) })
        root.addView(offres, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = dp(16) })
        return root
    }

    private fun bigCard(icon: Int, title: String, sub: String, primary: Boolean, onClick: () -> Unit): View {
        val fg = if (primary) Ui.ON_ACCENT else Ui.TEXT
        val sfg = if (primary) Ui.ON_ACCENT_SOFT else Ui.MUTED

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(24), dp(24), dp(24), dp(24))
        card.background = if (primary) Ui.ripple(this, Ui.ACCENT, 28)
        else Ui.ripple(this, Ui.SURFACE, 28, Ui.SURFACE2, 1)
        card.isClickable = true
        card.setOnClickListener { onClick() }

        val ic = ImageView(this)
        ic.setImageResource(icon)
        ic.setColorFilter(fg)
        ic.scaleType = ImageView.ScaleType.CENTER_INSIDE
        ic.setPadding(dp(12), dp(12), dp(12), dp(12))
        ic.background = Ui.shape(this, if (primary) 0x1F0D1117 else Ui.SURFACE2, 24)
        card.addView(ic, LinearLayout.LayoutParams(dp(48), dp(48)))

        card.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        card.addView(Ui.text(this, title, 30f, fg, true))
        card.addView(Ui.text(this, sub, 15f, sfg))
        return card
    }

    // ───────────────────────── actions ─────────────────────────

    private fun ensureCall(): Boolean {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 1)
        Toast.makeText(this, "Autorisez l'appel puis réessayez.", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun runSolde() {
        if (!ensureCall()) return
        showLoading()
        Ussd.run(this, "Solde", Store.cfg(this, "k_solde", "#357#"), "")
    }

    private fun openOffers() {
        show(Page.OFFERS)
        if (Store.menu(this).isEmpty() && UssdService.instance != null) refreshMenu()
    }

    private fun refreshMenu() {
        if (!ensureCall()) return
        if (UssdService.instance == null) { askAccessibility(); return }
        showLoading()
        Ussd.run(this, "Offres", Store.cfg(this, "k_offres", "#359#"), Store.cfg(this, "k_rep1", "1"), Ussd.MODE_MENU)
    }

    private fun pickOffer(m: MenuItem) {
        if (!ensureCall()) return
        if (UssdService.instance == null) { askAccessibility(); return }
        val replies = listOf(Store.cfg(this, "k_rep1", "1"), m.num, Store.cfg(this, "k_repfin", "1"))
            .filter { it.isNotBlank() }.joinToString(",")
        showLoading()
        Ussd.run(this, m.label, Store.cfg(this, "k_offres", "#359#"), replies)
    }

    private fun askAccessibility() {
        Ui.dialog(this, AlertDialog.Builder(this)
            .setTitle("Accessibilité requise")
            .setMessage("Pour parcourir automatiquement les menus des offres, activez « Yas USSD » dans les services d'accessibilité.")
            .setPositiveButton("Activer") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Plus tard", null))
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    // ───────────────────────── liste des offres ─────────────────────────

    private fun buildOffers(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(28), dp(20), 0)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ui.iconButton(this, R.drawable.ic_back) { show(Page.HOME) })
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.setPadding(dp(14), 0, dp(8), 0)
        titles.addView(Ui.text(this, "Offres", 22f, Ui.TEXT, true))
        titles.addView(Ui.text(this, "Touchez une offre pour voir ce qu'il vous reste", 13f, Ui.MUTED))
        top.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_refresh) { refreshMenu() })
        root.addView(top)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(20), 0, dp(24))

        val items = Store.menu(this)
        if (items.isEmpty()) {
            val box = Ui.card(this)
            box.setPadding(dp(20), dp(20), dp(20), dp(20))
            box.addView(Ui.text(this, "Aucune liste d'offres pour l'instant", 17f, Ui.TEXT, true))
            val hint = Ui.text(this, "Chargez la liste une première fois : elle sera gardée dans l'application.", 14f, Ui.MUTED)
            hint.setPadding(0, dp(6), 0, dp(16))
            box.addView(hint)
            box.addView(Ui.primaryButton(this, "Charger les offres") { refreshMenu() })
            list.addView(box)
        } else {
            items.forEach { list.addView(offerRow(it), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(10) }) }
            val t = Store.getLong(this, "menu_time")
            if (t > 0) {
                val foot = Ui.text(this, "Liste mise à jour le ${fmt.format(Date(t))}", 12f, Ui.MUTED)
                foot.setPadding(dp(4), dp(8), 0, 0)
                list.addView(foot)
            }
        }

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return root
    }

    private fun offerRow(m: MenuItem): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(16), dp(16), dp(16))
        row.background = Ui.ripple(this, Ui.SURFACE, 20)
        row.isClickable = true
        row.setOnClickListener { pickOffer(m) }

        val badge = Ui.text(this, m.num, 15f, Ui.ON_ACCENT, true)
        badge.gravity = Gravity.CENTER
        badge.background = Ui.shape(this, Ui.ACCENT, 20)
        row.addView(badge, LinearLayout.LayoutParams(dp(40), dp(40)))

        val label = Ui.text(this, m.label, 16f, Ui.TEXT)
        label.setPadding(dp(14), 0, dp(8), 0)
        row.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        row.addView(Ui.text(this, "›", 26f, Ui.MUTED))
        return row
    }

    // ───────────────────────── résultats ─────────────────────────

    private fun onUssdDone() {
        dismissLoading()
        if (page == Page.OFFERS) show(Page.OFFERS)
        handleUnseen()
    }

    private fun handleUnseen() {
        if (Store.get(this, "unseen") == "1") {
            Store.put(this, "unseen", "0")
            showResult()
        }
    }

    private fun showLoading() {
        dismissLoading()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(24), dp(22), dp(24), dp(22))
        val pb = ProgressBar(this)
        pb.indeterminateTintList = ColorStateList.valueOf(Ui.ACCENT)
        box.addView(pb, LinearLayout.LayoutParams(dp(32), dp(32)))
        val msg = Ui.text(this, "Réponse de l'opérateur…", 16f, Ui.TEXT)
        msg.setPadding(dp(18), 0, 0, 0)
        box.addView(msg)
        loading = Ui.dialog(this, AlertDialog.Builder(this).setView(box))
    }

    private fun dismissLoading() {
        try { loading?.dismiss() } catch (_: Exception) {}
        loading = null
    }

    private fun showResult() {
        val title = Store.get(this, "last_title")
        val text = Store.get(this, "last_text")
        val time = Store.getLong(this, "last_time")

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(22), dp(24), dp(4))
        box.addView(Ui.text(this, title, 20f, Ui.TEXT, true))
        box.addView(Ui.text(this, fmt.format(Date(time)), 12f, Ui.MUTED))
        val body: TextView = Ui.text(this, text, 16f, Ui.TEXT)
        body.setTextIsSelectable(true)
        body.setLineSpacing(0f, 1.15f)
        body.setPadding(0, dp(16), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(body)
        box.addView(sv)

        Ui.dialog(this, AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Copier") { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("USSD", text))
                Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show()
            })
    }
}
