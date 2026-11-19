package org.n0.absent_launch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

private class LauncherApp(val component: ComponentName, val label: String)

/** Lowercase and strip accents so "cafe" matches "Café". Run at search time; nothing stored. */
private fun fold(s: String): String {
    val d = Normalizer.normalize(s, Normalizer.Form.NFD)
    val sb = StringBuilder(d.length)
    for (c in d) {
        if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
    }
    return sb.toString().lowercase(Locale.ROOT)
}

/**
 * Memory-lean icon-free HOME activity. The app list lives only while the UI is visible:
 * it is dropped when the UI is hidden and reloaded on return.
 */
class LauncherActivity : Activity() {
    private var apps: List<LauncherApp> = emptyList()
    private lateinit var appAdapter: AppAdapter
    private lateinit var searchField: EditText
    private lateinit var appList: ListView
    private lateinit var refreshButton: Button
    /** Folded query last submitted by the user. */
    private var query = ""
    private var isRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.rgb(16, 16, 16)
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.rgb(16, 16, 16)
        }
        setContentView(createContentView())

        // Predictive back: onBackPressed is never called on API 33+ when enabled.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { clearSearch() }
            )
        }
    }

    /** Load the list when the UI becomes visible and it isn't already in memory. */
    override fun onStart() {
        super.onStart()
        if (apps.isEmpty()) refreshApps()
    }

    /** Free the list once the UI is hidden; it is rebuilt in onStart. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            apps = emptyList()
         
            appAdapter.replaceWith(emptyList())
        }
    }

    private fun createContentView(): View {
        val background = Color.rgb(16, 16, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        // Edge-to-edge is enforced on API 35+: pad for bars, cutout and keyboard.
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                v.setPadding(dp(20) + b.left, dp(12) + b.top, dp(20) + b.right, dp(8) + b.bottom)
                WindowInsets.CONSUMED
            }
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchField = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Color.rgb(150, 150, 150))
            setTextColor(Color.rgb(245, 245, 245))
            textSize = 18f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(110, 110, 110))
            contentDescription = "Search installed apps; press Search to filter"
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_UP
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                    applySearch()
                    true
                } else {
                    false
                }
            }
        }
        searchRow.addView(searchField, LinearLayout.LayoutParams(0, dp(52), 1f))

        refreshButton = Button(this).apply {
            text = "Refresh"
            textSize = 14f
            contentDescription = "Refresh app list"
            setOnClickListener { refreshApps() }
        }
        searchRow.addView(
            refreshButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52))
        )
        root.addView(searchRow)

        val listContainer = FrameLayout(this)
        appList = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(42, 42, 42))
            dividerHeight = dp(1)
            cacheColorHint = background
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val emptyMessage = TextView(this).apply {
            text = "No apps found"
            setTextColor(Color.rgb(170, 170, 170))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        listContainer.addView(
            appList,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        listContainer.addView(
            emptyMessage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        appList.emptyView = emptyMessage
        root.addView(
            listContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        appAdapter = AppAdapter()
        appList.adapter = appAdapter
        appList.setOnItemClickListener { _, _, position, _ -> launchApp(appAdapter.getItem(position)) }
        appList.setOnItemLongClickListener { _, _, position, _ ->
            openAppSettings(appAdapter.getItem(position))
            true
        }
        return root
    }

    /** Apply the typed search after the user submits it. A single match launches immediately. */
    private fun applySearch() {
        query = fold(searchField.text.toString().trim())
        showMatchingApps()
        hideKeyboard()
        if (query.isNotEmpty() && appAdapter.count == 1) {
            launchApp(appAdapter.getItem(0))
        }
    }

    private fun clearSearch() {
        searchField.text.clear()
        searchField.clearFocus()
        query = ""
        showMatchingApps()
        appList.setSelection(0)
        hideKeyboard()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    /** Query launcher activities on a short-lived worker thread; no package monitoring. */
    private fun refreshApps() {
        if (isRefreshing) return
        isRefreshing = true
        refreshButton.isEnabled = false

        Thread({
            var result: List<LauncherApp>? = null
            try {
                val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val infos = launcherApps.getActivityList(null, Process.myUserHandle())
                val list = ArrayList<LauncherApp>(infos.size)
                for (info in infos) {
                    val component = info.componentName
                    list.add(LauncherApp(component, info.label?.toString() ?: component.packageName))
                }
                // No CollationKey array per app: slower compare, but no extra allocation.
                val collator = Collator.getInstance()
                list.sortWith { a, b -> collator.compare(a.label, b.label) }
                result = list
            } catch (_: Exception) {
            }

            runOnUiThread {
                isRefreshing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshButton.isEnabled = true
                val loaded = result
                if (loaded == null) {
                    Toast.makeText(this, "Refresh failed", Toast.LENGTH_SHORT).show()
                } else {
                    apps = loaded
                    showMatchingApps()
                }
            }
        }, "app-list-refresh").apply {
            isDaemon = true
            start()
        }
    }

    private fun showMatchingApps() {
        if (!::appAdapter.isInitialized) return
        appAdapter.replaceWith(if (query.isEmpty()) apps else matches(query))
    }

    /** Prefix matches first, then other substring matches, in one result list. */
    private fun matches(q: String): List<LauncherApp> {
        val out = ArrayList<LauncherApp>()
        for (app in apps) {
            if (fold(app.label).startsWith(q)) out.add(app)
        }
        for (app in apps) {
            val key = fold(app.label)
            if (!key.startsWith(q) && key.contains(q)) out.add(app)
        }
        return out
    }

    private fun launchApp(app: LauncherApp) {
        hideKeyboard()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            refreshApps() // App was probably removed.
        } catch (_: SecurityException) {
            refreshApps()
        }
    }

    private fun openAppSettings(app: LauncherApp) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", app.component.packageName, null)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open app settings", Toast.LENGTH_SHORT).show()
        }
    }

    /** Home pressed while already open (singleTask): reset to a clean state. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            clearSearch()
        }
    }

    /** Pre-API 33 only. A home screen never finishes on back. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        clearSearch()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** Reuses one plain TextView per visible row; app icons are intentionally not loaded. */
    private inner class AppAdapter : BaseAdapter() {
        private var visibleApps: List<LauncherApp> = emptyList()

        fun replaceWith(items: List<LauncherApp>) {
            visibleApps = items
            notifyDataSetChanged()
        }

        override fun getCount(): Int = visibleApps.size
        override fun getItem(position: Int): LauncherApp = visibleApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? TextView) ?: TextView(this@LauncherActivity).apply {
                setTextColor(Color.rgb(245, 245, 245))
                textSize = 18f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(12))
                minHeight = dp(48)
                isSingleLine = true
            }
            row.text = visibleApps[position].label
            return row
        }
    }
}
