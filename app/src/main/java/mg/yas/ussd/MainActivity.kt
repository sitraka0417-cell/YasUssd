package mg.yas.ussd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    /** Appelé à chaque ouverture de l'appli, y compris quand elle était restée en arrière-plan. */
    override fun onResume() {
        super.onResume()
        Ussd.onChange = { runOnUiThread { onUssdChange() } }
        Ussd.snapshotProvider = { blurredSnapshot() }
        show(page)
        handleUnseen()
        Auto.schedule(this)
        autoRefresh()
    }

    override fun onPause() {
        super.onPause()
        Ussd.onChange = null
        Ussd.snapshotProvider = null
    }

    /** Capture de l'accueil, réduite 16 fois : une fois étirée par l'écran de chargement, elle apparaît floutée. */
    private fun blurredSnapshot(): Bitmap? {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null
        // L'accueil vient souvent d'être reconstruit : on force la mise en page, sinon la capture est vide (noire).
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(frame.left, frame.top, frame.left + w, frame.top + h)
        val small = Bitmap.createBitmap(maxOf(w / 16, 1), maxOf(h / 16, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(small)
        c.scale(small.width.toFloat() / w, small.height.toFloat() / h)
        frame.draw(c)
        // Deuxième réduction pour un flou plus doux.
        return Bitmap.createScaledBitmap(small, maxOf(small.width / 2, 1), maxOf(small.height / 2, 1), true)
    }

    private var homeScroll: ScrollView? = null

    private fun show(p: Page) {
        page = p
        val y = homeScroll?.scrollY ?: 0
        frame.removeAllViews()
        frame.addView(buildHome(), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        homeScroll?.post { homeScroll?.scrollTo(0, y) }
    }

    private fun onUssdChange() {
        if (isFinishing) return
        show(page)
        handleUnseen()
    }

    // ───────────────────────── mise à jour automatique ─────────────────────────

    private fun soldeJob() =
        Ussd.Job("Solde", Store.cfg(this, "k_solde", "#357#"), "", Ussd.MODE_RESULT, "solde")

    private fun offerJob(): Ussd.Job? {
        val num = Store.get(this, "sel_num")
        if (num.isEmpty()) return null
        val replies = listOf(Store.cfg(this, "k_rep1", "1"), num, Store.cfg(this, "k_repfin", "1"))
            .filter { it.isNotBlank() }.joinToString(",")
        return Ussd.Job(Store.get(this, "sel_label").ifEmpty { "Forfait" },
            Store.cfg(this, "k_offres", "#359#"), replies, Ussd.MODE_RESULT, "offer", 1)
    }

    private fun menuJob() =
        Ussd.Job("Liste des offres", Store.cfg(this, "k_offres", "#359#"),
            Store.cfg(this, "k_rep1", "1"), Ussd.MODE_MENU, "menu")

    /** Solde puis forfait : mis à jour à chaque ouverture, au plus une fois toutes les 20 secondes. */
    private fun autoRefresh() {
        if (page != Page.HOME || Ussd.active || !hasCall()) return
        if (System.currentTimeMillis() - Store.getLong(this, "last_end") < 20_000L) return
        refreshAll(true)
    }

    /** Après 3 échecs de suite, la mise à jour automatique d'un code attend 30 min (le bouton ↻ reste libre). */
    private fun backoff(key: String): Boolean =
        Store.getLong(this, "f_$key") >= 3 && System.currentTimeMillis() - Store.getLong(this, "te_$key") < 30 * 60_000L

    private fun refreshAll(auto: Boolean = false) {
        if (Ussd.active) { busyToast(); return }
        if (!ensureCall()) return
        val list = mutableListOf(soldeJob())
        if (UssdService.instance != null) (offerJob() ?: menuJob()).let { list.add(it) }
        val todo = if (auto) list.filter { !backoff(it.key) } else list
        if (todo.isNotEmpty()) Ussd.start(this, todo)
    }

    private fun refreshSolde() {
        if (Ussd.active) { busyToast(); return }
        if (!ensureCall()) return
        Ussd.start(this, listOf(soldeJob()))
    }

    private fun refreshOffer() {
        if (Ussd.active) { busyToast(); return }
        if (!ensureCall()) return
        if (UssdService.instance == null) { askAccessibility(); return }
        val j = offerJob()
        if (j == null) { openOffers(); return }
        Ussd.start(this, listOf(j))
    }

    private fun refreshMenu() {
        if (Ussd.active) { busyToast(); return }
        if (!ensureCall()) return
        if (UssdService.instance == null) { askAccessibility(); return }
        Ussd.start(this, listOf(menuJob()))
    }

    private fun pickOffer(m: MenuItem) {
        if (Ussd.active) { busyToast(); return }
        if (!ensureCall()) return
        if (UssdService.instance == null) { askAccessibility(); return }
        Store.put(this, "sel_num", m.num)
        Store.put(this, "sel_label", m.label)
        Store.put(this, "r_offer", "")
        Store.putLong(this, "t_offer", 0L)
        Store.put(this, "e_offer", "")
        show(Page.HOME)
        offerJob()?.let { Ussd.start(this, listOf(it)) }
    }

    private fun openOffers() {
        if (Store.menu(this).isEmpty() && UssdService.instance != null && !Ussd.active) refreshMenu()
    }

    private fun busyToast() = Toast.makeText(this, "Une mise à jour est déjà en cours.", Toast.LENGTH_SHORT).show()

    // ───────────────────────── accueil ─────────────────────────

    private fun buildHome(): View {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        homeScroll = sv
        sv.addView(buildHomeContent(), FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        return sv
    }

    private fun buildHomeContent(): View {
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
        top.addView(Ui.iconButton(this, R.drawable.ic_refresh) { refreshAll() },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(8) })
        top.addView(Ui.iconButton(this, R.drawable.ic_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(top)

        if (!hasCall()) {
            root.addView(banner("Autorisation d'appel requise : touchez ici pour l'accorder.") { ensureCall() },
                LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(16) })
        } else if (UssdService.instance == null) {
            root.addView(banner("Service d'accessibilité désactivé : touchez ici pour l'activer (nécessaire pour mettre à jour le forfait en arrière-plan).") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(16) })
        }

        // Carte solde
        val soldeText = Store.get(this, "r_solde")
        val (sSub, sErr) = status("solde")
        val solde = bigCard(
            R.drawable.ic_wallet, "Solde",
            if (soldeText.isEmpty()) "Touchez ↻ pour voir votre crédit" else clean(soldeText),
            soldeText, sSub, sErr, true, { refreshSolde() }, { refreshSolde() })

        // Carte offre
        val label = Store.get(this, "sel_label")
        val offerText = Store.get(this, "r_offer")
        val (oSub, oErr) = status("offer")
        val body = when {
            label.isEmpty() -> "Choisissez votre offre dans la liste ci-dessous."
            offerText.isEmpty() -> if (Ussd.isBusy("offer")) "Lecture du forfait…" else "Touchez ↻ pour voir ce qu'il vous reste."
            else -> clean(offerText)
        }
        val offres = bigCard(
            R.drawable.ic_list, if (label.isEmpty()) "Offres" else label, body, offerText,
            if (label.isEmpty()) "" else oSub + expSuffix(), oErr, false,
            { openOffers() }, { refreshOffer() })

        root.addView(solde, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(20) })
        root.addView(offres, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(14) })
        addOfferList(root)
        return root
    }

    private fun banner(text: String, onClick: () -> Unit): View {
        val b = Ui.text(this, text, 13f, Ui.ACCENT)
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.background = Ui.ripple(this, Ui.SURFACE, 14)
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun expSuffix(): String {
        val e = Store.getLong(this, "exp")
        return if (e > 0) " · expire le ${short.format(Date(e))}" else ""
    }

    private fun clean(t: String) = t.trim().replace(Regex("\n{2,}"), "\n")

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000L
        return when {
            m < 1 -> "à l'instant"
            m < 60 -> "il y a $m min"
            m < 1440 -> "il y a ${m / 60} h"
            else -> "le ${short.format(Date(t))}"
        }
    }

    /** Ligne d'état d'une carte : (texte, est-ce une erreur). Le dernier bon résultat reste affiché même après un échec. */
    private fun status(key: String): Pair<String, Boolean> {
        if (Ussd.isBusy(key)) return "Mise à jour en cours…" to false
        val err = Store.get(this, "e_$key")
        val te = Store.getLong(this, "te_$key")
        val t = Store.getLong(this, "t_$key")
        if (err.isNotEmpty() && te >= t) {
            val old = if (t > 0) " Dernier résultat : ${short.format(Date(t))}." else ""
            return "⚠ ${err.replace("\n", " ").take(110)}$old" to true
        }
        if (t > 0) return "Mis à jour ${ago(t)}" to false
        return "" to false
    }

    private fun bigCard(
        icon: Int, title: String, body: String, rawText: String, sub: String, subIsError: Boolean, primary: Boolean,
        onClick: () -> Unit, onRefresh: () -> Unit
    ): View {
        val fg = if (primary) Ui.ON_ACCENT else Ui.TEXT
        val sfg = if (primary) Ui.ON_ACCENT_SOFT else Ui.MUTED
        val chip = if (primary) 0x1F0D1117 else Ui.SURFACE2

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(22), dp(18), dp(22), dp(16))
        card.minimumHeight = dp(150)
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

        // Zone de texte défilante : le résultat est copié tel quel ; appui long = copier.
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.isClickable = true
        inner.setOnClickListener { onClick() }
        if (rawText.isNotEmpty()) inner.setOnLongClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("USSD", rawText))
            Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show()
            true
        }
        inner.addView(Ui.text(this, title, 15f, sfg, true), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10) })
        val tv = Ui.text(this, body, if (rawText.isEmpty()) 17f else 20f, fg, rawText.isNotEmpty())
        tv.setLineSpacing(0f, 1.1f)
        tv.setPadding(0, dp(4), 0, dp(6))
        inner.addView(tv)
        card.addView(inner, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        if (sub.isNotEmpty()) {
            val st = Ui.text(this, sub, 12f, if (subIsError && !primary) Ui.BAD else sfg)
            if (subIsError) st.setTypeface(null, Typeface.BOLD)
            st.setPadding(0, dp(6), 0, 0)
            card.addView(st)
        }
        return card
    }

    // ───────────────────────── autorisations ─────────────────────────

    private fun ensureCall(): Boolean {
        if (hasCall()) return true
        requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE), 1)
        Toast.makeText(this, "Autorisez l'appel puis réessayez.", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun askAccessibility() {
        Ui.dialog(this, AlertDialog.Builder(this)
            .setTitle("Accessibilité requise")
            .setMessage("Pour lire les offres sans afficher les menus du téléphone, activez « Yas USSD » dans les services d'accessibilité.")
            .setPositiveButton("Activer") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Plus tard", null))
    }

    private fun askPermissions() {
        val p = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    // ───────────────────────── liste des offres (sur l'accueil) ─────────────────────────

    private fun addOfferList(root: LinearLayout) {
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(4), dp(24), 0, dp(10))
        head.addView(Ui.text(this, "Mes offres", 18f, Ui.TEXT, true), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(Ui.iconButton(this, R.drawable.ic_refresh) { refreshMenu() })
        root.addView(head)

        val items = Store.menu(this)
        val loadingMenu = Ussd.isBusy("menu") || (items.isEmpty() && Ussd.isBusy("offer"))
        val menuErr = Store.get(this, "e_menu")
        if (menuErr.isNotEmpty() && !loadingMenu) {
            val e = Ui.text(this, "⚠ $menuErr", 13f, Ui.BAD, true)
            e.setPadding(dp(4), 0, dp(4), dp(10))
            root.addView(e)
        }
        if (items.isEmpty()) {
            val msg = if (loadingMenu) "Chargement de la liste…" else "Liste non chargée : touchez ↻"
            val t = Ui.text(this, msg, 14f, Ui.MUTED)
            t.setPadding(dp(4), 0, 0, dp(10))
            root.addView(t)
        } else {
            val sel = Store.get(this, "sel_num")
            items.forEach { root.addView(offerRow(it, it.num == sel), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(10) }) }
            val t = Store.getLong(this, "menu_time")
            if (t > 0) {
                val foot = Ui.text(this, "Touchez votre offre pour voir ce qu'il reste · liste du ${fmt.format(Date(t))}", 12f, Ui.MUTED)
                foot.setPadding(dp(4), dp(4), 0, dp(8))
                root.addView(foot)
            }
        }
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

    // ───────────────────────── boîte de résultat (code libre) ─────────────────────────

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
