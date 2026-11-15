package org.n0.speedy_launch

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Collator
import java.util.LinkedHashMap
import java.util.Locale

/** A small, icon-free HOME activity with on-demand app-list refresh. */
class LauncherActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val apps = ArrayList<LauncherApp>()
    private lateinit var appAdapter: AppAdapter
    private lateinit var searchField: EditText
    private lateinit var appList: ListView
    private lateinit var refreshButton: Button
    private var query = ""
    private var isRefreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.statusBarColor = Color.rgb(16, 16, 16)
        window.navigationBarColor = Color.rgb(16, 16, 16)
        setContentView(createContentView())
        refreshApps()
    }

    private fun createContentView(): View {
        val background = Color.rgb(16, 16, 16)
        val textColor = Color.rgb(245, 245, 245)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchField = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Color.rgb(150, 150, 150))
            setTextColor(textColor)
            textSize = 18f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(110, 110, 110))
            contentDescription = "Search installed apps"
        }
        searchRow.addView(searchField, LinearLayout.LayoutParams(
            0,
            dp(52),
            1f
        ))
        refreshButton = Button(this).apply {
            text = "Refresh"
            textSize = 14f
            contentDescription = "Refresh app list"
            setOnClickListener { refreshApps() }
        }
        searchRow.addView(refreshButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(52)
        ))
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
            visibility = View.GONE
        }
        listContainer.addView(appList, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        listContainer.addView(emptyMessage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        appList.emptyView = emptyMessage
        root.addView(listContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        appAdapter = AppAdapter()
        appList.adapter = appAdapter
        appList.setOnItemClickListener { _, _, position, _ -> launchApp(appAdapter.getItem(position)) }
        appList.setOnItemLongClickListener { _, _, position, _ ->
            openAppSettings(appAdapter.getItem(position))
            true
        }
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                showMatchingApps()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        return root
    }

    /** Query installed launcher apps on a temporary worker thread; no automatic package monitoring. */
    private fun refreshApps() {
        if (isRefreshing) return
        isRefreshing = true
        refreshButton.isEnabled = false

        Thread({
            val refreshedApps = try {
                val uniqueApps = LinkedHashMap<String, LauncherApp>()
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val activities = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                for (activity in activities) {
                    val packageName = activity.activityInfo?.packageName ?: continue
                    if (!uniqueApps.containsKey(packageName)) {
                        val label = activity.loadLabel(packageManager)?.toString() ?: packageName
                        uniqueApps[packageName] = LauncherApp(packageName, label)
                    }
                }
                val collator = Collator.getInstance()
                uniqueApps.values.sortedWith { first, second ->
                    collator.compare(first.label, second.label)
                }
            } catch (_: Exception) {
                null
            }

            mainHandler.post {
                isRefreshing = false
                if (isFinishing || isDestroyed) return@post
                refreshButton.isEnabled = true
                refreshedApps?.let {
                    apps.clear()
                    apps.addAll(it)
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
        appAdapter.replaceWith(apps.filter { it.label.lowercase(Locale.ROOT).contains(query) })
    }

    private fun launchApp(app: LauncherApp) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchField.windowToken, 0)
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (_: android.content.ActivityNotFoundException) {
                // The user can tap Refresh to update the list if an app was removed.
            }
        }
    }

    private fun openAppSettings(app: LauncherApp) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", app.packageName, null)
        }
        startActivity(intent)
    }

    override fun onBackPressed() {
        if (searchField.text.isNotEmpty()) {
            searchField.text.clear()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class LauncherApp(val packageName: String, val label: String)

    /** Reuses one plain TextView per visible row; app icons are intentionally not loaded. */
    private inner class AppAdapter : BaseAdapter() {
        private val visibleApps = ArrayList<LauncherApp>()

        fun replaceWith(items: List<LauncherApp>) {
            visibleApps.clear()
            visibleApps.addAll(items)
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
