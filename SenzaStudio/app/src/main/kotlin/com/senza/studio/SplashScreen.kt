package com.senza.studio

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Layar sambutan: logo, tombol pintasan fitur, dan Get Started. */
class SplashScreen(act: MainActivity) : Screen(act) {

    override val showsNav = false

    override val view: View = ScrollView(act).apply {
        isFillViewport = true
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(24), act.dp(32), act.dp(24), act.dp(24))
        }
        col.addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(ImageView(act).apply {
            setImageResource(R.drawable.logo_s)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(act.dp(150), WRAP))
        col.addView(act.label("Senza Studio", 34f, Pal.text, true).apply { gravity = Gravity.CENTER }, act.lp(t = 8))
        col.addView(
            act.label("Build Your Android App\nAnywhere, Anytime", 15f, Pal.sub).apply { gravity = Gravity.CENTER },
            act.lp(t = 8, b = 26)
        )

        val row1 = LinearLayout(act).apply { gravity = Gravity.CENTER }
        val row2 = LinearLayout(act).apply { gravity = Gravity.CENTER }
        row1.addView(shortcut(act, R.drawable.ic_code, "Code Editor") { act.openCodeEditor() })
        row1.addView(shortcut(act, R.drawable.ic_preview, "UI Preview") { act.navigate(act.preview) })
        row1.addView(shortcut(act, R.drawable.ic_build, "Build APK") { act.navigate(act.build) })
        row2.addView(shortcut(act, R.drawable.ic_folder, "Project Explorer") { act.navigate(act.explorer) })
        row2.addView(shortcut(act, R.drawable.ic_terminal, "Terminal") { act.navigate(act.terminal) })
        col.addView(row1, act.lp())
        col.addView(row2, act.lp(t = 14))

        col.addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(act.primaryButton("Get Started", R.drawable.ic_plus) { act.navigate(act.explorer) }, act.lp(t = 28))
        col.addView(
            act.label("Powered by SenzaProject", 12f, Pal.primaryLight).apply { gravity = Gravity.CENTER },
            act.lp(t = 16)
        )
        addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun shortcut(act: MainActivity, res: Int, name: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
            val circle = FrameLayout(act).apply {
                background = act.glassPress(30, Pal.glassStrong)
                addView(act.icon(res, 26, Pal.primaryLight).apply {
                    layoutParams = FrameLayout.LayoutParams(act.dp(26), act.dp(26), Gravity.CENTER)
                })
            }
            addView(circle, LinearLayout.LayoutParams(act.dp(62), act.dp(62)))
            addView(act.label(name, 12f, Pal.text).apply { gravity = Gravity.CENTER }, act.lp(WRAP, WRAP, t = 8))
            layoutParams = LinearLayout.LayoutParams(act.dp(104), WRAP)
        }
}
