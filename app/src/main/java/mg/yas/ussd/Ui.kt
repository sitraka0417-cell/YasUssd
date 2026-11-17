package mg.yas.ussd

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Petits utilitaires de design partagés (couleurs, cartes, boutons, boîtes de dialogue). */
object Ui {
    val BG = 0xFF0D1117.toInt()
    val SURFACE = 0xFF171C26.toInt()
    val SURFACE2 = 0xFF232A38.toInt()
    val ACCENT = 0xFFFFC72C.toInt()
    val ON_ACCENT = 0xFF0D1117.toInt()
    val ON_ACCENT_SOFT = 0xAA0D1117.toInt()
    val TEXT = 0xFFF1F3F7.toInt()
    val MUTED = 0xFF9BA5B7.toInt()
    val OK = 0xFF4CD27B.toInt()
    val BAD = 0xFFFF7A7A.toInt()

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun shape(c: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(c, radiusDp).toFloat()
        g.setColor(color)
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), strokeColor)
        return g
    }

    fun ripple(c: Context, color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(c, color, radiusDp, strokeColor, strokeDp), null)

    fun text(c: Context, t: String, sp: Float, color: Int = TEXT, bold: Boolean = false): TextView {
        val v = TextView(c)
        v.text = t
        v.textSize = sp
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    fun iconButton(c: Context, res: Int, onClick: () -> Unit): ImageButton {
        val b = ImageButton(c)
        b.setImageResource(res)
        b.setColorFilter(TEXT)
        b.scaleType = ImageView.ScaleType.CENTER_INSIDE
        b.setPadding(dp(c, 11), dp(c, 11), dp(c, 11), dp(c, 11))
        b.background = ripple(c, SURFACE, 16)
        b.layoutParams = LinearLayout.LayoutParams(dp(c, 46), dp(c, 46))
        b.setOnClickListener { onClick() }
        return b
    }

    fun input(c: Context, hint: String, value: String = "", phone: Boolean = false): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setText(value)
        e.textSize = 16f
        e.setTextColor(TEXT)
        e.setHintTextColor(MUTED)
        e.inputType = if (phone) InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT
        e.setSingleLine(true)
        e.background = shape(c, SURFACE2, 12)
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        return e
    }

    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.background = shape(c, SURFACE, 20)
        l.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4))
        return l
    }

    fun section(c: Context, title: String): TextView {
        val t = text(c, title, 14f, MUTED, true)
        t.setPadding(dp(c, 6), dp(c, 24), 0, dp(c, 8))
        return t
    }

    fun row(c: Context, title: String, sub: String, onClick: (() -> Unit)?): View {
        val r = LinearLayout(c)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        if (onClick != null) {
            r.background = ripple(c, SURFACE, 16)
            r.isClickable = true
            r.setOnClickListener { onClick() }
        }
        r.addView(text(c, title, 16f, TEXT, true))
        if (sub.isNotEmpty()) r.addView(text(c, sub, 13f, MUTED))
        return r
    }

    fun primaryButton(c: Context, label: String, onClick: () -> Unit): TextView {
        val t = text(c, label, 16f, ON_ACCENT, true)
        t.gravity = android.view.Gravity.CENTER
        t.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14))
        t.background = ripple(c, ACCENT, 16)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun dialog(c: Context, b: AlertDialog.Builder): AlertDialog {
        val d = b.create()
        d.window?.setBackgroundDrawable(InsetDrawable(shape(c, SURFACE, 24), dp(c, 12)))
        try { d.show() } catch (_: Exception) {}
        return d
    }
}
