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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private companion object {
        const val FAV = "fav"
        const val ALL = "*"
        val KINDS = listOf("live", "vod", "series")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var cacheFile: File
    private lateinit var adapter: ChannelAdapter
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var groupTitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var count: TextView

    private var all: List<Channel> = emptyList()
    private var byKind: Map<String, List<Channel>> = emptyMap()
    private var shown: List<Channel> = emptyList()
    private val favorites = HashSet<String>()

    // Navigation state: home -> section (live/vod/series/fav) -> group -> [episodes]
    private var rootSection: String? = null   // set when the list has a single kind (skip home)
    private var section: String? = null       // null = home
    private var groupName: String? = null     // null = list of groups; ALL = everything in the section
    private var episodes: List<Channel>? = null
    private var episodesTitle = ""
    private var query = ""

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goBack()
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importLocal(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("iptv", Context.MODE_PRIVATE)
        cacheFile = File(filesDir, "playlist.m3u")
        favorites.addAll(prefs.getStringSet("favs", emptySet()).orEmpty())
        onBackPressedDispatcher.addCallback(this, backCallback)

        groupTitle = findViewById(R.id.group_title)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        count = findViewById(R.id.count)
        searchBox = findViewById(R.id.search)

        adapter = ChannelAdapter(
            isFavorite = { it.url in favorites },
            onClick = { onChannelClick(it) },
            onLongClick = { toggleFavorite(it) }
        )
        groupAdapter = GroupAdapter { onGroupClick(it) }
        recycler = findViewById(R.id.list)
        recycler.layoutManager = LinearLayoutManager(this)

        searchBox.doAfterTextChanged {
            query = it?.toString().orEmpty()
            render()
        }
        findViewById<Button>(R.id.btn_add).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        empty.setOnClickListener { showAddDialog() }

        render()
        if (cacheFile.exists()) loadCached()
        updateEmpty()
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

    // ------------------------------------------------------------- navigation

    private fun setChannels(list: List<Channel>) {
        all = list
        byKind = list.groupBy { it.kind }
        val present = KINDS.filter { byKind[it].orEmpty().isNotEmpty() }
        rootSection = if (present.size == 1) present[0] else null
        section = rootSection
        groupName = null
        episodes = null
        query = ""
        searchBox.setText("")
        render()
        updateEmpty()
    }

    private fun kindLabel(kind: String) = when (kind) {
        "live" -> getString(R.string.kind_live)
        "vod" -> getString(R.string.kind_vod)
        "series" -> getString(R.string.kind_series)
        else -> getString(R.string.favorites)
    }

    private fun canGoBack() =
        episodes != null || query.isNotBlank() || groupName != null || (section != null && section != rootSection)

    private fun goBack() {
        when {
            episodes != null -> episodes = null
            query.isNotBlank() -> { query = ""; searchBox.setText(""); }
            groupName != null -> {
                groupName = null
                if (section == FAV) section = rootSection
            }
            section != null && section != rootSection -> section = null
        }
        render()
    }

    private fun homeItems(): List<GroupItem> {
        val items = KINDS.mapNotNull { k ->
            val n = byKind[k].orEmpty().size
            if (n > 0) GroupItem(kindLabel(k), n, "k:$k") else null
        }
        val fav = all.count { it.url in favorites }
        return items + GroupItem(getString(R.string.favorites), fav, "k:$FAV")
    }

    private fun categoryItems(kind: String): List<GroupItem> {
        val items = byKind[kind].orEmpty()
        val counts = items.groupingBy { it.group }.eachCount()
        return listOf(GroupItem(getString(R.string.all_in_section), items.size, "g:$ALL")) +
            counts.keys.sorted().map { GroupItem(it, counts[it] ?: 0, "g:$it") }
    }

    private fun onGroupClick(g: GroupItem) {
        when {
            g.key.startsWith("k:") -> {
                section = g.key.removePrefix("k:")
                groupName = if (section == FAV) ALL else null
            }
            g.key.startsWith("g:") -> groupName = g.key.removePrefix("g:")
        }
        render()
    }

    private fun currentItems(): List<Channel> {
        var base = when {
            section == FAV -> all.filter { it.url in favorites }
            section != null -> byKind[section ?: ""].orEmpty()
            else -> all
        }
        val g = groupName
        if (g != null && g != ALL && section != FAV) base = base.filter { it.group == g }
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) base = base.filter { it.name.lowercase().contains(q) }
        return base
    }

    private fun render() {
        val eps = episodes
        when {
            eps != null -> showChannels(eps, episodesTitle)
            query.isNotBlank() -> showChannels(currentItems(), "")
            section == null -> showGroups(homeItems(), "")
            section != FAV && groupName == null -> showGroups(categoryItems(section!!), kindLabel(section!!))
            else -> {
                val title = kindLabel(section!!) + (groupName?.takeIf { it != ALL && section != FAV }?.let { " › $it" } ?: "")
                showChannels(currentItems(), title)
            }
        }
        backCallback.isEnabled = canGoBack()
    }

    private fun showGroups(items: List<GroupItem>, title: String) {
        if (recycler.adapter !== groupAdapter) recycler.adapter = groupAdapter
        groupAdapter.submitList(if (all.isEmpty()) emptyList() else items)
        groupTitle.text = title
        count.text = ""
    }

    private fun showChannels(list: List<Channel>, title: String) {
        if (recycler.adapter !== adapter) recycler.adapter = adapter
        shown = list
        adapter.submitList(list)
        groupTitle.text = title
        count.text = getString(R.string.items_count, list.size)
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
                render()
            }.onFailure { showError(it) }
        }
    }

    private fun toggleFavorite(position: Int) {
        val ch = shown.getOrNull(position) ?: return
        val added = favorites.add(ch.url)
        if (!added) favorites.remove(ch.url)
        prefs.edit().putStringSet("favs", HashSet(favorites)).apply()
        Toast.makeText(this, if (added) R.string.added_fav else R.string.removed_fav, Toast.LENGTH_SHORT).show()
        if (section == FAV && episodes == null) render() else adapter.notifyItemChanged(position)
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
