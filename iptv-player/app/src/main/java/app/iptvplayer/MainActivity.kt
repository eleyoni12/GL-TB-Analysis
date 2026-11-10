package app.iptvplayer

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private companion object {
        const val FAV = "fav"
        const val ALL = "*"
        const val RESUME = "~resume"
        val KINDS = listOf("live", "vod", "series")
        val YEAR = Regex("(?:19|20)\\d{2}")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var cacheFile: File
    private lateinit var adapter: ChannelAdapter
    private lateinit var sectionAdapter: ChipAdapter
    private lateinit var groupAdapter: ChipAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var sectionsRv: RecyclerView
    private lateinit var groupsRv: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var groupTitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var listEmpty: TextView
    private lateinit var count: TextView
    private lateinit var topBlock: View

    private var lib: Library = Library(emptyList(), emptyMap(), emptyMap(), emptyMap())
    private var shown: List<Channel> = emptyList()
    private val favorites = HashSet<String>()

    private var section = "live"
    private var group = ALL
    private var query = ""
    private var lastPoster = false
    private var returnedFromPlayer = false
    private var topHidden = false
    private var searchJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { renderList() }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                topHidden -> {
                    // First Back brings the header (search + sections) back.
                    setTopVisible(true)
                    sectionsRv.post { sectionsRv.getChildAt(0)?.requestFocus() }
                    return
                }
                query.isNotBlank() -> { query = ""; searchBox.setText("") }
            }
            renderList()
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importLocal(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.load(this)
        setContentView(R.layout.activity_main)

        History.init(this)
        prefs = getSharedPreferences("iptv", Context.MODE_PRIVATE)
        cacheFile = File(filesDir, "playlist.m3u")
        favorites.addAll(prefs.getStringSet("favs", emptySet()).orEmpty())
        Epg.source = prefs.getString("source_url", null)
        onBackPressedDispatcher.addCallback(this, backCallback)

        groupTitle = findViewById(R.id.group_title)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        listEmpty = findViewById(R.id.list_empty)
        count = findViewById(R.id.count)
        searchBox = findViewById(R.id.search)
        sectionsRv = findViewById(R.id.chips_sections)
        groupsRv = findViewById(R.id.chips_groups)
        recycler = findViewById(R.id.list)
        topBlock = findViewById(R.id.top_block)

        applyTheme()

        adapter = ChannelAdapter(
            isFavorite = { it.url in favorites },
            progressOf = { History.progress(it.url) },
            onClick = { onChannelClick(it) },
            onLongClick = { toggleFavorite(it) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.setItemViewCacheSize(20)
        // Scrolling down hides the header/search/chips so the list gets the whole screen.
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 6 && rv.canScrollVertically(-1)) setTopVisible(false)
                else if (dy < -6 || !rv.canScrollVertically(-1)) setTopVisible(true)
            }
        })

        sectionAdapter = ChipAdapter { onSectionClick(it) }
        groupAdapter = ChipAdapter { onGroupClick(it) }
        for ((rv, ad) in listOf(sectionsRv to sectionAdapter, groupsRv to groupAdapter)) {
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = ad
            rv.itemAnimator = null
        }
        groupsRv.visibility = View.GONE

        searchBox.doAfterTextChanged {
            query = it?.toString().orEmpty()
            handler.removeCallbacks(searchRunnable)
            handler.postDelayed(searchRunnable, 250) // don't filter on every keystroke
        }
        findViewById<Button>(R.id.btn_add).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { SettingsDialog.show(this) { recreate() } }
        empty.setOnClickListener { showAddDialog() }

        if (cacheFile.exists()) loadCached()
        updateEmpty()
    }

    override fun onResume() {
        super.onResume()
        if (returnedFromPlayer && lib.all.isNotEmpty()) {
            returnedFromPlayer = false
            // Keep the scroll position; only refresh progress bars (and the resume list itself).
            if (group == RESUME) renderList() else adapter.notifyDataSetChanged()
        }
    }

    /** Colors come from [AppTheme] so the user can change them. */
    private fun applyTheme() {
        val d = resources.displayMetrics.density
        findViewById<View>(R.id.root).setBackgroundColor(AppTheme.bg)
        findViewById<View>(R.id.header).background = AppTheme.headerDrawable(d)
        findViewById<TextView>(R.id.app_title).setTextColor(AppTheme.accent)
        findViewById<TextView>(R.id.app_subtitle_tv).setTextColor(AppTheme.muted)
        searchBox.apply {
            background = AppTheme.searchSelector(d)
            setTextColor(AppTheme.text)
            setHintTextColor(AppTheme.muted)
        }
        groupTitle.setTextColor(AppTheme.accent)
        count.setTextColor(AppTheme.muted)
        empty.setTextColor(AppTheme.text)
        listEmpty.setTextColor(AppTheme.muted)
        findViewById<Button>(R.id.btn_add).apply {
            backgroundTintList = ColorStateList.valueOf(AppTheme.accent)
            setTextColor(AppTheme.onAccent)
        }
        findViewById<Button>(R.id.btn_refresh).setTextColor(AppTheme.accent)
        findViewById<Button>(R.id.btn_settings).setTextColor(AppTheme.accent)
        window.statusBarColor = AppTheme.header
        window.navigationBarColor = AppTheme.bg
    }

    private fun setTopVisible(visible: Boolean) {
        if (visible == !topHidden) return
        topHidden = !visible
        topBlock.visibility = if (visible) View.VISIBLE else View.GONE
        backCallback.isEnabled = query.isNotBlank() || topHidden
    }

    // ---------------------------------------------------------------- loading

    private fun loadCached() {
        lifecycleScope.launch {
            setLoading(true)
            val result = runCatching { withContext(Dispatchers.IO) { Playlist.parse(cacheFile) } }
            setLoading(false)
            result.onSuccess { setLibrary(it) }
        }
    }

    private fun refresh() {
        val url = prefs.getString("source_url", null)
        if (url == null) {
            Toast.makeText(this, R.string.no_refresh_source, Toast.LENGTH_LONG).show()
            showAddDialog()
        } else loadFromUrl(url)
    }

    private fun loadFromUrl(url: String) {
        lifecycleScope.launch {
            setLoading(true)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val used = Playlist.downloadSmart(url, cacheFile)
                    used to Playlist.parse(cacheFile)
                }
            }
            setLoading(false)
            result.onSuccess { (used, library) ->
                prefs.edit().putString("source_url", used).apply()
                Epg.source = used
                setLibrary(library)
                Toast.makeText(
                    this@MainActivity,
                    getString(
                        R.string.loaded_summary,
                        library.byKind["live"].orEmpty().size,
                        library.byKind["vod"].orEmpty().size,
                        library.byKind["series"].orEmpty().size
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { showError(it) }
        }
    }

    private fun importLocal(uri: Uri) {
        lifecycleScope.launch {
            setLoading(true)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        cacheFile.outputStream().use { input.copyTo(it) }
                    }
                    Playlist.parse(cacheFile)
                }
            }
            setLoading(false)
            result.onSuccess {
                prefs.edit().remove("source_url").apply()
                Epg.source = null
                setLibrary(it)
            }.onFailure { showError(it) }
        }
    }

    private fun showError(t: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.load_failed_title)
            .setMessage(t.message ?: t.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setLoading(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) empty.visibility = View.GONE else updateEmpty()
    }

    private fun updateEmpty() {
        empty.visibility = if (lib.all.isEmpty() && progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ chips

    private fun setLibrary(l: Library) {
        lib = l
        section = KINDS.firstOrNull { lib.byKind[it].orEmpty().isNotEmpty() } ?: FAV
        group = ALL
        query = ""
        searchBox.setText("")
        sectionAdapter.submit(
            (KINDS.filter { lib.byKind[it].orEmpty().isNotEmpty() } + FAV).map { ChipAdapter.Item(it, kindLabel(it)) },
            section
        )
        rebuildGroupChips()
        renderList()
        updateEmpty()
        focusFirstChipOnTv()
    }

    private fun focusFirstChipOnTv() {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            sectionsRv.post { sectionsRv.getChildAt(0)?.requestFocus() }
        }
    }

    private fun kindLabel(kind: String) = when (kind) {
        "live" -> getString(R.string.kind_live)
        "vod" -> getString(R.string.kind_vod)
        "series" -> getString(R.string.kind_series)
        else -> getString(R.string.favorites)
    }

    private fun onSectionClick(k: String) {
        if (k == section) return
        section = k
        group = ALL
        sectionAdapter.select(k)
        rebuildGroupChips()
        renderList()
    }

    private fun onGroupClick(k: String) {
        group = k
        groupAdapter.select(k)
        renderList()
    }

    private fun yearOf(name: String): Int = YEAR.findAll(name).maxOfOrNull { it.value.toInt() } ?: -1

    /** Live keeps the server order; movies/series follow the user's choice (default: newest year first). */
    private fun orderedGroups(kind: String): List<String> {
        val raw = lib.groupsByKind[kind].orEmpty()
        if (kind == "live") return raw
        return when (prefs.getInt("cat_sort", 0)) {
            1 -> raw
            2 -> raw.sortedBy { it.lowercase() }
            else -> raw.sortedByDescending { yearOf(it) } // stable: same year keeps the server order
        }
    }

    private fun rebuildGroupChips() {
        if (lib.all.isEmpty() || section == FAV) {
            groupsRv.visibility = View.GONE
            return
        }
        groupsRv.visibility = View.VISIBLE
        val keys = listOf(RESUME, ALL) + orderedGroups(section)
        groupAdapter.submit(
            keys.map {
                ChipAdapter.Item(
                    it,
                    when (it) {
                        RESUME -> getString(R.string.resume_watching)
                        ALL -> getString(R.string.all_in_section)
                        else -> it
                    }
                )
            },
            group
        )
        groupsRv.scrollToPosition(0)
    }

    // ------------------------------------------------------------------- list

    /** The list for the current section/category, before any search. Cheap: lookups, no filtering. */
    private fun baseItems(): List<Channel> = when {
        group == RESUME -> History.recent(section)
        section == FAV -> lib.all.filter { it.url in favorites }
        group == ALL -> lib.byKind[section].orEmpty()
        else -> lib.byGroup[Library.key(section, group)].orEmpty()
    }

    private fun spanCount(): Int {
        val dm = resources.displayMetrics
        return (dm.widthPixels / dm.density / 140f).toInt().coerceIn(2, 7)
    }

    private fun renderList() {
        searchJob?.cancel()
        val base = baseItems()
        val q = query.trim()
        if (q.isEmpty()) {
            show(base)
        } else {
            // Search runs off the main thread so typing/navigation never freezes.
            searchJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) { base.filter { it.name.contains(q, ignoreCase = true) } }
                if (q == query.trim()) show(result)
            }
        }
        backCallback.isEnabled = query.isNotBlank() || topHidden
    }

    private fun show(items: List<Channel>) {
        val wantPoster = section == "vod" || section == "series"
        if (wantPoster != lastPoster) {
            lastPoster = wantPoster
            adapter.poster = wantPoster
            recycler.layoutManager = if (wantPoster) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
            recycler.adapter = adapter
        }
        shown = items
        adapter.setItems(items)
        recycler.scrollToPosition(0)
        groupTitle.text = ""
        count.text = getString(R.string.items_count, items.size)
        listEmpty.setText(if (group == RESUME && query.isBlank()) R.string.nothing_to_resume else R.string.no_results)
        listEmpty.visibility = if (lib.all.isNotEmpty() && items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- actions

    private fun hasXtream(): Boolean =
        Playlist.xtreamCreds(prefs.getString("source_url", null).orEmpty()) != null

    private fun onChannelClick(position: Int) {
        val ch = shown.getOrNull(position) ?: return
        returnedFromPlayer = true

        // Movies and series open a details page (story, cast, trailer) before playing.
        val viaDetails = group != RESUME && hasXtream() &&
            (ch.url.startsWith(Playlist.SERIES_SCHEME) || ch.kind == "vod")
        if (viaDetails) {
            Session.selected = ch
            startActivity(Intent(this, DetailsActivity::class.java))
            return
        }

        val playable = shown.filter { !it.url.startsWith(Playlist.SERIES_SCHEME) }
        Session.channels = playable
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", playable.indexOf(ch).coerceAtLeast(0)))
    }

    private fun toggleFavorite(position: Int) {
        val ch = shown.getOrNull(position) ?: return
        val added = favorites.add(ch.url)
        if (!added) favorites.remove(ch.url)
        prefs.edit().putStringSet("favs", HashSet(favorites)).apply()
        Toast.makeText(this, if (added) R.string.added_fav else R.string.removed_fav, Toast.LENGTH_SHORT).show()
        if (section == FAV) renderList() else adapter.notifyItemChanged(position)
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add, null)
        val etUrl = view.findViewById<EditText>(R.id.et_url)
        val etServer = view.findViewById<EditText>(R.id.et_server)
        val etUser = view.findViewById<EditText>(R.id.et_user)
        val etPass = view.findViewById<EditText>(R.id.et_pass)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title)
            .setView(view)
            .setPositiveButton(R.string.load, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        view.findViewById<Button>(R.id.btn_file).setOnClickListener {
            dialog.dismiss()
            pickFile.launch("*/*")
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = etUrl.text.toString().trim()
                val server = etServer.text.toString()
                val user = etUser.text.toString()
                val pass = etPass.text.toString()
                // Xtream details win when completely filled; otherwise use the M3U link.
                val finalUrl = when {
                    server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() ->
                        Playlist.xtreamUrl(server, user, pass)
                    url.isNotEmpty() -> url
                    else -> null
                }
                if (finalUrl == null) {
                    Toast.makeText(this, R.string.no_input, Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    loadFromUrl(finalUrl)
                }
            }
        }
        dialog.show()
    }
}
