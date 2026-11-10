package app.iptvplayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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

    private lateinit var prefs: SharedPreferences
    private lateinit var cacheFile: File
    private lateinit var adapter: ChannelAdapter
    private lateinit var spinner: Spinner
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var count: TextView

    private var all: List<Channel> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var groupNames: List<String> = emptyList()
    private var groupPos = 0
    private var query = ""
    private val favorites = HashSet<String>()

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importLocal(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("iptv", Context.MODE_PRIVATE)
        cacheFile = File(filesDir, "playlist.m3u")
        favorites.addAll(prefs.getStringSet("favs", emptySet()).orEmpty())

        spinner = findViewById(R.id.group_spinner)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        count = findViewById(R.id.count)

        adapter = ChannelAdapter(
            isFavorite = { it.url in favorites },
            onClick = { play(it) },
            onLongClick = { toggleFavorite(it) }
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString().orEmpty()
            applyFilter()
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                groupPos = pos
                applyFilter()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        findViewById<Button>(R.id.btn_add).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        empty.setOnClickListener { showAddDialog() }

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

    // -------------------------------------------------------------- filtering

    private fun setChannels(list: List<Channel>) {
        all = list
        groupNames = list.map { it.group }.distinct().sorted()
        val labels = listOf(getString(R.string.all_channels), getString(R.string.favorites)) + groupNames
        groupPos = 0
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(0)
        applyFilter()
        updateEmpty()
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val group = if (groupPos >= 2) groupNames.getOrNull(groupPos - 2) else null
        shown = all.filter { ch ->
            val groupOk = when (groupPos) {
                0 -> true
                1 -> ch.url in favorites
                else -> ch.group == group
            }
            groupOk && (q.isEmpty() || ch.name.lowercase().contains(q))
        }
        adapter.submitList(shown)
        count.text = getString(R.string.channels_count, shown.size)
    }

    // ---------------------------------------------------------------- actions

    private fun play(position: Int) {
        Session.channels = shown
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", position))
    }

    private fun toggleFavorite(position: Int) {
        val ch = shown.getOrNull(position) ?: return
        val added = favorites.add(ch.url)
        if (!added) favorites.remove(ch.url)
        prefs.edit().putStringSet("favs", HashSet(favorites)).apply()
        Toast.makeText(this, if (added) R.string.added_fav else R.string.removed_fav, Toast.LENGTH_SHORT).show()
        if (groupPos == 1) applyFilter() else adapter.notifyItemChanged(position)
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
