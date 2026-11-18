package mg.yas.ussd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    private val short = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun hasCall() = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

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
        handleUnseen()
        autoRefresh()
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

    // ───────────────────────── mise à jour automatique ─────────────────────────

    /** Solde d'abord (en arrière-plan), puis forfait restant de l'offre choisie, si les données ont plus de quelques minutes. */
    private fun autoRefresh() {
        if (Ussd.active || page != Page.HOME || !hasCall()) return
        if (System.currentTimeMillis() - Store.getLong(this, "t_solde") > 60_000L) {
            runSolde()
            return
        }
        refreshOffer(false)
    }

    private fun onUssdDone() {
        show(page)
        handleUnseen()
        autoRefresh()
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

        // Carte solde
        val soldeBusy = Ussd.active && Ussd.runningKey == "solde"
        val soldeText = Store.get(this, "r_solde")
        val soldeTime = Store.getLong(this, "t_solde")
        val solde = bigCard(
            R.drawable.ic_wallet, "Solde",
            if (soldeText.isEmpty()) "Touchez pour voir votre crédit" else clean(soldeText),
            subLine(soldeBusy, soldeTime), true,
            { runSolde() }, { runSolde() })

        // Carte offre
        val label = Store.get(this, "sel_label")
        val offerBusy = Ussd.active && Ussd.runningKey == "offer"
        val offerText = Store.get(this, "r_offer")
        val offerTime = Store.getLong(this, "t_offer")
        val body = when {
            label.isEmpty() -> "Choisissez votre offre pour voir ce qu'il vous reste"
            offerText.isEmpty() -> "Actualisez pour voir ce qu'il vous reste"
            else -> clean(offerText)
        }
        val offres = bigCard(
            R.drawable.ic_list, if (label.isEmpty()) "Offres" else label, body,
            if (label.isEmpty()) "" else subLine(offerBusy, offerTime) + " · touchez pour changer d'offre", false,
            { openOffers() },
            { if (label.isEmpty()) openOffers() else if (UssdService.instance == null) askAccessibility() else refreshOffer(true) })

        root.addView(solde, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = dp(24) })
        root.addView(offres, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = dp(16) })
        return root
    }

    private fun clean(t: String) = t.trim().replace(Regex("\n{2,}"), "\n")

    private fun subLine(busy: Boolean, time: Long): String =
        if (busy) "Mise à jour…" else if (time > 0) "Mis à jour le ${short.format(Date(time))}" else ""

    private fun bigCard(
        icon: Int, title: String, body: String, sub: String, primary: Boolean,
        onClick: () -> Unit, onRefresh: () -> Unit
    ): View {
        val fg = if (primary) Ui.ON_ACCENT else Ui.TEXT
        val sfg = if (primary) Ui.ON_ACCENT_SOFT else Ui.MUTED
        val chip = if (primary) 0x1F0D1117 else Ui.SURFACE2

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(22), dp(20), dp(22), dp(20))
        card.background = if (primary) Ui.ripple(this, Ui.ACCENT, 28)
        else Ui.ripple(this, Ui.SURFACE, 28, Ui.SURFACE2, 1)
        card.isClickable = true
        card.setOnClickListener { onClick() }

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val ic = ImageView(this)
        ic.setImageResource(icon)
        ic.setColorFilter(fg)
        ic.scaleType = ImageView.ScaleType.CENTER_INSIDE
        ic.setPadding(dp(11), dp(11), dp(11), dp(11))
        ic.background = Ui.shape(this, chip, 22)
        head.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val rf = ImageView(this)
        rf.setImageResource(R.drawable.ic_refresh)
        rf.setColorFilter(fg)
        rf.scaleType = ImageView.ScaleType.CENTER_INSIDE
        rf.setPadding(dp(11), dp(11), dp(11), dp(11))
        rf.background = Ui.ripple(this, chip, 22)
        rf.isClickable = true
        rf.setOnClickListener { onRefresh() }
        head.addView(rf, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(head)

        card.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        card.addView(Ui.text(this, title, 15f, sfg, true))
        val tv = Ui.text(this, body, 20f, fg, true)
        tv.maxLines = 6
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setLineSpacing(0f, 1.1f)
        tv.setPadding(0, dp(4), 0, dp(6))
        card.addView(tv)
        if (sub.isNotEmpty()) card.addView(Ui.text(this, sub, 12f, sfg))
        return card
    }

    // ───────────────────────── actions ─────────────────────────

    private fun ensureCall(): Boolean {
        if (hasCall()) return true
        requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 1)
        Toast.makeText(this, "Autorisez l'appel puis réessayez.", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun runSolde() {
        if (Ussd.active || !ensureCall()) return
        Ussd.run(this, "Solde", Store.cfg(this, "k_solde", "#357#"), "", Ussd.MODE_RESULT, "solde")
        if (page == Page.HOME) show(Page.HOME)
    }

    private fun openOffers() {
        show(Page.OFFERS)
        if (Store.menu(this).isEmpty() && UssdService.instance != null) refreshMenu()
    }

    private fun refreshMenu() {
        if (Ussd.active || !ensureCall()) return
        if (UssdService.instance == null) { askAccessibility(); return }
        Ussd.run(this, "Offres", Store.cfg(this, "k_offres", "#359#"), Store.cfg(this, "k_rep1", "1"), Ussd.MODE_MENU, "menu")
        if (page == Page.OFFERS) show(Page.OFFERS)
    }

    private fun startOffer(num: String, label: String) {
        val replies = listOf(Store.cfg(this, "k_rep1", "1"), num, Store.cfg(this, "k_repfin", "1"))
            .filter { it.isNotBlank() }.joinToString(",")
        Ussd.run(this, label, Store.cfg(this, "k_offres", "#359#"), replies, Ussd.MODE_RESULT, "offer")
        if (page == Page.HOME) show(Page.HOME)
    }

    /** Relit le forfait restant de l'offre choisie (en silence). */
    private fun refreshOffer(force: Boolean) {
        val num = Store.get(this, "sel_num")
        if (num.isEmpty() || Ussd.active || !hasCall() || UssdService.instance == null) return
        if (!force && System.currentTimeMillis() - Store.getLong(this, "t_offer") < 300_000L) return
        startOffer(num, Store.get(this, "sel_label"))
    }

    private fun pickOffer(m: MenuItem) {
        if (Ussd.active || !ensureCall()) return
        if (UssdService.instance == null) { askAccessibility(); return }
        Store.put(this, "sel_num", m.num)
        Store.put(this, "sel_label", m.label)
        Store.put(this, "r_offer", "")
        Store.putLong(this, "t_offer", 0L)
        show(Page.HOME)
        startOffer(m.num, m.label)
    }

    private fun askAccessibility() {
        Ui.dialog(this, AlertDialog.Builder(this)
            .setTitle("Accessibilité requise")
            .setMessage("Pour lire les offres sans afficher les menus, activez « Yas USSD » dans les services d'accessibilité.")
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
        titles.addView(Ui.text(this, "Touchez votre offre : le reste s'affiche sur l'accueil", 13f, Ui.MUTED))
        top.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        top.addView(Ui.iconButton(this, R.drawable.ic_refresh) { refreshMenu() })
        root.addView(top)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(20), 0, dp(24))

        val items = Store.menu(this)
        val loadingMenu = Ussd.active && Ussd.runningKey == "menu"
        if (items.isEmpty()) {
            val box = Ui.card(this)
            box.setPadding(dp(20), dp(20), dp(20), dp(20))
            if (loadingMenu) {
                box.addView(Ui.text(this, "Chargement de la liste…", 17f, Ui.TEXT, true))
            } else {
                box.addView(Ui.text(this, "Aucune liste d'offres pour l'instant", 17f, Ui.TEXT, true))
                val hint = Ui.text(this, "Chargez la liste une première fois : elle sera gardée dans l'application.", 14f, Ui.MUTED)
                hint.setPadding(0, dp(6), 0, dp(16))
                box.addView(hint)
                box.addView(Ui.primaryButton(this, "Charger les offres") { refreshMenu() })
            }
            list.addView(box)
        } else {
            val sel = Store.get(this, "sel_num")
            items.forEach { list.addView(offerRow(it, it.num == sel), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(10) }) }
            val t = Store.getLong(this, "menu_time")
            if (t > 0) {
                val foot = Ui.text(this, if (loadingMenu) "Mise à jour de la liste…" else "Liste mise à jour le ${fmt.format(Date(t))}", 12f, Ui.MUTED)
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

    private fun offerRow(m: MenuItem, selected: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(16), dp(16), dp(16))
        row.background = if (selected) Ui.ripple(this, Ui.SURFACE, 20, Ui.ACCENT, 2) else Ui.ripple(this, Ui.SURFACE, 20)
        row.isClickable = true
        row.setOnClickListener { pickOffer(m) }

        val badge = Ui.text(this, m.num, 15f, Ui.ON_ACCENT, true)
        badge.gravity = Gravity.CENTER
        badge.background = Ui.shape(this, Ui.ACCENT, 20)
        row.addView(badge, LinearLayout.LayoutParams(dp(40), dp(40)))

        val label = Ui.text(this, m.label, 16f, Ui.TEXT)
        label.setPadding(dp(14), 0, dp(8), 0)
        row.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))

        row.addView(Ui.text(this, if (selected) "✓" else "›", 24f, if (selected) Ui.ACCENT else Ui.MUTED))
        return row
    }

    // ───────────────────────── boîte de résultat (erreurs, menu illisible) ─────────────────────────

    private fun handleUnseen() {
        if (Store.get(this, "unseen") == "1") {
            Store.put(this, "unseen", "0")
            showResult()
        }
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
