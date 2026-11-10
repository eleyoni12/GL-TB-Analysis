package app.iptvplayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
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
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private companion object {
        const val FAV = "fav"
        const val ALL = "*"
        const val RESUME = "~resume"
        val KINDS = listOf("live", "vod", "series")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var cacheFile: File
    private lateinit var adapter: ChannelAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var groupTitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var listEmpty: TextView
    private lateinit var count: TextView
    private lateinit var sectionChips: ChipGroup
    private lateinit var groupChips: ChipGroup
    private lateinit var groupScroll: View

    private var all: List<Channel> = emptyList()
    private var byKind: Map<String, List<Channel>> = emptyMap()
    private var groupsByKind: Map<String, List<String>> = emptyMap()
    private var shown: List<Channel> = emptyList()
    private val favorites = HashSet<String>()

    private var section = "live"
    private var group = ALL
    private var episodes: List<Channel>? = null
    private var episodesTitle = ""
    private var query = ""
    private var lastPoster = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                episodes != null -> episodes = null
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
        setContentView(R.layout.activity_main)

        History.init(this)
        prefs = getSharedPreferences("iptv", Context.MODE_PRIVATE)
        cacheFile = File(filesDir, "playlist.m3u")
        favorites.addAll(prefs.getStringSet("favs", emptySet()).orEmpty())
        onBackPressedDispatcher.addCallback(this, backCallback)

        groupTitle = findViewById(R.id.group_title)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        listEmpty = findViewById(R.id.list_empty)
        count = findViewById(R.id.count)
        searchBox = findViewById(R.id.search)
        sectionChips = findViewById(R.id.chips_sections)
        groupChips = findViewById(R.id.chips_groups)
        groupScroll = findViewById(R.id.scroll_groups)

        adapter = ChannelAdapter(
            isFavorite = { it.url in favorites },
            progressOf = { History.progress(it.url) },
            onClick = { onChannelClick(it) },
            onLongClick = { toggleFavorite(it) }
        )
        recycler = findViewById(R.id.list)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        searchBox.doAfterTextChanged {
            query = it?.toString().orEmpty()
            renderList()
        }
        groupTitle.setOnClickListener {
            if (episodes != null) {
                episodes = null
                renderList()
            }
        }
        findViewById<Button>(R.id.btn_add).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        empty.setOnClickListener { showAddDialog() }

        groupScroll.visibility = View.GONE
        if (cacheFile.exists()) loadCached()
        updateEmpty()
    }

    override fun onResume() {
        super.onResume()
        if (all.isNotEmpty()) {
            renderList()
            adapter.notifyDataSetChanged() // refresh progress bars after watching
        }
    }

    // ---------------------------------------------------------------- loading

    private fun loadCached() {
        lifecycleScope.launch {
            setLoading(true)
            val result = runCatching { withContext(Dispatchers.IO) { Playlist.parse(cacheFile) } }
            setLoading(false)
            result.onSuccess { setChannels(it) }
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
                    Playlist.downloadSmart(url, cacheFile)
                    Playlist.parse(cacheFile)
                }
            }
            setLoading(false)
            result.onSuccess {
                prefs.edit().putString("source_url", url).apply()
                setChannels(it)
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
                setChannels(it)
            }.onFailure { showError(it) }
        }
    }

    private fun showError(t: Throwable) {
        Toast.makeText(this, getString(R.string.loading_failed, t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
    }

    private fun setLoading(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) empty.visibility = View.GONE else updateEmpty()
    }

    private fun updateEmpty() {
        empty.visibility = if (all.isEmpty() && progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ chips

    private fun setChannels(list: List<Channel>) {
        all = list
        byKind = list.groupBy { it.kind }
        groupsByKind = byKind.mapValues { e -> e.value.map { it.group }.distinct().sorted() }
        section = KINDS.firstOrNull { byKind[it].orEmpty().isNotEmpty() } ?: FAV
        group = ALL
        episodes = null
        query = ""
        searchBox.setText("")
        rebuildSectionChips()
        rebuildGroupChips()
        renderList()
        updateEmpty()
    }

    private fun kindLabel(kind: String) = when (kind) {
        "live" -> getString(R.string.kind_live)
        "vod" -> getString(R.string.kind_vod)
        "series" -> getString(R.string.kind_series)
        else -> getString(R.string.favorites)
    }

    private fun makeChip(label: String, checked: Boolean, onClick: () -> Unit): Chip =
        Chip(this).apply {
            text = label
            isCheckable = true
            isCheckedIconVisible = false
            chipStrokeWidth = 0f
            chipBackgroundColor = AppCompatResources.getColorStateList(this@MainActivity, R.color.chip_bg)
            setTextColor(AppCompatResources.getColorStateList(this@MainActivity, R.color.chip_text))
            isChecked = checked
            setOnClickListener { onClick() }
        }

    private fun rebuildSectionChips() {
        sectionChips.removeAllViews()
        val keys = KINDS.filter { byKind[it].orEmpty().isNotEmpty() } + FAV
        for (k in keys) {
            sectionChips.addView(makeChip(kindLabel(k), k == section) {
                if (section != k) {
                    section = k
                    group = ALL
                    episodes = null
                    rebuildGroupChips()
                    renderList()
                }
            })
        }
    }

    private fun rebuildGroupChips() {
        groupChips.removeAllViews()
        if (all.isEmpty() || section == FAV) {
            groupScroll.visibility = View.GONE
            return
        }
        groupScroll.visibility = View.VISIBLE
        val keys = listOf(RESUME, ALL) + groupsByKind[section].orEmpty()
        for (k in keys) {
            val label = when (k) {
                RESUME -> getString(R.string.resume_watching)
                ALL -> getString(R.string.all_in_section)
                else -> k
            }
            groupChips.addView(makeChip(label, k == group) {
                group = k
                episodes = null
                renderList()
            })
        }
    }

    // ------------------------------------------------------------------- list

    private fun currentItems(): List<Channel> {
        var base = when {
            group == RESUME -> History.recent(section)
            section == FAV -> all.filter { it.url in favorites }
            group == ALL -> byKind[section].orEmpty()
            else -> byKind[section].orEmpty().filter { it.group == group }
        }
        val q = query.trim()
        if (q.isNotEmpty()) base = base.filter { it.name.contains(q, ignoreCase = true) }
        return base
    }

    private fun spanCount(): Int {
        val dm = resources.displayMetrics
        return (dm.widthPixels / dm.density / 120f).toInt().coerceIn(2, 8)
    }

    private fun renderList() {
        val eps = episodes
        val items = eps ?: currentItems()
        val wantPoster = eps == null && (section == "vod" || section == "series")
        if (wantPoster != lastPoster) {
            lastPoster = wantPoster
            adapter.poster = wantPoster
            recycler.layoutManager = if (wantPoster) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
            recycler.adapter = adapter
        }
        shown = items
        adapter.submitList(items)
        groupTitle.text = if (eps != null) "→  $episodesTitle" else ""
        count.text = getString(R.string.items_count, items.size)
        listEmpty.setText(if (group == RESUME && eps == null) R.string.nothing_to_resume else R.string.no_results)
        listEmpty.visibility = if (all.isNotEmpty() && items.isEmpty()) View.VISIBLE else View.GONE
        backCallback.isEnabled = eps != null || query.isNotBlank()
    }

    // ---------------------------------------------------------------- actions

    private fun onChannelClick(position: Int) {
        val ch = shown.getOrNull(position) ?: return
        if (ch.url.startsWith(Playlist.SERIES_SCHEME)) {
            openSeries(ch)
            return
        }
        val playable = shown.filter { !it.url.startsWith(Playlist.SERIES_SCHEME) }
        Session.channels = playable
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", playable.indexOf(ch).coerceAtLeast(0)))
    }

    private fun openSeries(ch: Channel) {
        val src = prefs.getString("source_url", null)
        if (src == null || Playlist.xtreamCreds(src) == null) {
            Toast.makeText(this, R.string.not_xtream, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            setLoading(true)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    Playlist.fetchEpisodes(src, ch.url.removePrefix(Playlist.SERIES_SCHEME), ch.name, ch.logo, cacheDir)
                }
            }
            setLoading(false)
            result.onSuccess {
                episodes = it
                episodesTitle = ch.name
                renderList()
            }.onFailure { showError(it) }
        }
    }

    private fun toggleFavorite(position: Int) {
        val ch = shown.getOrNull(position) ?: return
        val added = favorites.add(ch.url)
        if (!added) favorites.remove(ch.url)
        prefs.edit().putStringSet("favs", HashSet(favorites)).apply()
        Toast.makeText(this, if (added) R.string.added_fav else R.string.removed_fav, Toast.LENGTH_SHORT).show()
        if (section == FAV && episodes == null) renderList() else adapter.notifyItemChanged(position)
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add, null)
        val etUrl = view.findViewById<EditText>(R.id.et_url)
        val etServer = view.findViewById<EditText>(R.id.et_server)
        val etUser = view.findViewById<EditText>(R.id.et_user)
        val etPass = view.findViewById<EditText>(R.id.et_pass)
        etUrl.setText(prefs.getString("source_url", "")?.takeIf { !it.contains("get.php?username=") }.orEmpty())

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
                val finalUrl = when {
                    url.isNotEmpty() -> url
                    server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() ->
                        Playlist.xtreamUrl(server, user, pass)
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
