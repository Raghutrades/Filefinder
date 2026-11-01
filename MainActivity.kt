package com.zraghu.filefinder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class Item(
    val name: String,
    val path: String,
    val size: Long,
    val time: Long,
    val isDir: Boolean,
    val type: String
)

class MainActivity : Activity() {

    private val all = ArrayList<Item>()
    private var shown = ArrayList<Item>()
    private var indexing = false
    private var indexedOnce = false

    private lateinit var search: EditText
    private lateinit var sortSpinner: Spinner
    private lateinit var typeSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var list: ListView

    private val sorts = listOf("Name A-Z", "Name Z-A", "Newest", "Oldest", "Largest", "Smallest")
    private val types = listOf("All", "Images", "Videos", "Audio", "Documents", "APKs", "Archives", "Folders")
    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // lets us open/share files from their path
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)

        search = EditText(this)
        search.hint = "Search files..."
        search.isSingleLine = true
        root.addView(search, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        sortSpinner = Spinner(this)
        sortSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sorts)
        typeSpinner = Spinner(this)
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        val rescan = Button(this)
        rescan.text = "Rescan"
        row.addView(sortSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(typeSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(rescan, LinearLayout.LayoutParams(-2, -2))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this)
        status.setPadding(0, pad / 2, 0, pad / 2)
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        list = ListView(this)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })

        val sel = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { refresh() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sortSpinner.onItemSelectedListener = sel
        typeSpinner.onItemSelectedListener = sel

        rescan.setOnClickListener { startIndex() }

        list.setOnItemClickListener { _, _, pos, _ -> openItem(shown[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> showMenu(shown[pos]); true }
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (!indexedOnce && !indexing) startIndex()
        } else {
            askAccess()
        }
    }

    private fun hasAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun askAccess() {
        status.text = "Please allow 'All files access' for this app, then come back."
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 1
            )
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasAccess()) startIndex()
    }

    private fun startIndex() {
        if (indexing || !hasAccess()) return
        indexing = true
        status.text = "Scanning..."
        Thread {
            val found = ArrayList<Item>()
            val stack = ArrayDeque<File>()
            stack.add(Environment.getExternalStorageDirectory())
            var count = 0
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                val kids = dir.listFiles() ?: continue
                for (f in kids) {
                    val isDir = f.isDirectory
                    found.add(
                        Item(
                            f.name, f.absolutePath,
                            if (isDir) 0L else f.length(),
                            f.lastModified(), isDir,
                            if (isDir) "Folders" else typeOf(f.name)
                        )
                    )
                    if (isDir) stack.add(f)
                    count++
                    if (count % 3000 == 0) {
                        runOnUiThread { status.text = "Scanning... $count items found" }
                    }
                }
            }
            runOnUiThread {
                all.clear()
                all.addAll(found)
                indexing = false
                indexedOnce = true
                refresh()
            }
        }.start()
    }

    private fun typeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "Images"
            "mp4", "mkv", "avi", "mov", "3gp", "webm" -> "Videos"
            "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus" -> "Audio"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub" -> "Documents"
            "apk", "xapk" -> "APKs"
            "zip", "rar", "7z", "tar", "gz" -> "Archives"
            else -> "Other"
        }
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        val words = search.text.toString().lowercase().split(" ").filter { it.isNotBlank() }
        val t = typeSpinner.selectedItem.toString()
        val filtered = all.filter { i ->
            (t == "All" || i.type == t) && words.all { i.path.lowercase().contains(it) }
        }
        val sorted = when (sortSpinner.selectedItemPosition) {
            0 -> filtered.sortedBy { it.name.lowercase() }
            1 -> filtered.sortedByDescending { it.name.lowercase() }
            2 -> filtered.sortedByDescending { it.time }
            3 -> filtered.sortedBy { it.time }
            4 -> filtered.sortedByDescending { it.size }
            else -> filtered.sortedBy { it.size }
        }
        shown = ArrayList(sorted.take(3000))
        status.text = if (indexing) "Scanning..." else
            "${sorted.size} results" + (if (sorted.size > 3000) " (showing first 3000)" else "")

        list.adapter = object : ArrayAdapter<Item>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, shown
        ) {
            override fun getView(p: Int, c: View?, parent: ViewGroup): View {
                val v = super.getView(p, c, parent)
                val it = getItem(p)!!
                v.findViewById<TextView>(android.R.id.text1).text =
                    (if (it.isDir) "\uD83D\uDCC1 " else "") + it.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    (if (it.isDir) "" else fmtSize(it.size) + "  •  ") +
                            dateFmt.format(Date(it.time)) + "\n" + it.path
                return v
            }
        }
    }

    private fun fmtSize(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024)
    }

    private fun openItem(i: Item) {
        if (i.isDir) {
            search.setText(i.path.removePrefix(Environment.getExternalStorageDirectory().absolutePath).trim('/'))
            search.setSelection(search.text.length)
            return
        }
        try {
            val ext = i.name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.fromFile(File(i.path)), mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(i: Item) {
        AlertDialog.Builder(this)
            .setTitle(i.name)
            .setItems(arrayOf("Show path", "Share", "Delete")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this).setMessage(i.path).setPositiveButton("OK", null).show()
                    1 -> share(i)
                    2 -> confirmDelete(i)
                }
            }.show()
    }

    private fun share(i: Item) {
        if (i.isDir) return
        try {
            val ext = i.name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = mime
            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(File(i.path)))
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(i: Item) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage(i.path + "\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val f = File(i.path)
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (ok) {
                    all.removeAll { it.path == i.path || it.path.startsWith(i.path + "/") }
                    refresh()
                } else {
                    Toast.makeText(this, "Could not delete", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
