package app.zmby.backup

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Downloads use MediaStore; removable storage uses the user's persisted directory grant. */
class BackupFiles(private val context: Context) {
    data class Entry(val uri: Uri, val name: String, val source: String, val header: BackupHeader?, val encrypted: Boolean)
    private val resolver get() = context.contentResolver
    private val prefs = context.getSharedPreferences("backup_files", Context.MODE_PRIVATE)
    val tree: Uri? get() = prefs.getString("usb_tree", null)?.let(Uri::parse)
    fun grant(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("usb_tree", uri.toString()).apply()
    }
    fun read(uri: Uri): String = resolver.openInputStream(uri)?.use { input ->
        val bytes = input.readBytesLimited(BackupCodec.MAX_BYTES)
        bytes.toString(Charsets.UTF_8)
    } ?: error("无法读取备份文件")

    fun save(text: String, usb: Boolean): String {
        val name = "zmby-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.zmby"
        val uri: Uri
        if(!usb && Build.VERSION.SDK_INT < 29) {
            @Suppress("DEPRECATION") val folder=java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"zmby")
            require(folder.exists() || folder.mkdirs()) { "无法创建备份目录" }
            uri=Uri.fromFile(java.io.File(folder,name))
        } else if (usb) {
            val root = tree ?: error("请先选择 U 盘目录")
            val doc = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
            val folder = children(root, DocumentsContract.getTreeDocumentId(root)).firstOrNull { it.second == "zmby" }?.first
                ?: DocumentsContract.createDocument(resolver, doc, DocumentsContract.Document.MIME_TYPE_DIR, "zmby") ?: error("无法创建备份目录")
            uri = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name) ?: error("无法创建备份文件")
        } else {
            if (Build.VERSION.SDK_INT < 29) error("此系统请通过手机备份，或选择存储目录")
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/zmby")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建备份文件")
        }
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("无法写入备份")
            if (!usb && Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { runCatching { if(uri.scheme == "file") java.io.File(uri.path!!).delete() else if (usb) DocumentsContract.deleteDocument(resolver, uri) else resolver.delete(uri, null, null) }; throw e }
        if (!usb) list().filter { it.source == "本机存储" }.drop(10).forEach { if(it.uri.scheme == "file") java.io.File(it.uri.path!!).delete() else resolver.delete(it.uri, null, null) }
        return name
    }

    fun list(): List<Entry> {
        val found = mutableListOf<Pair<Uri,Pair<String,String>>>()
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?", arrayOf("${Environment.DIRECTORY_DOWNLOADS}/zmby/"), "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { c ->
                while(c.moveToNext()) found += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0).toString()) to (c.getString(1) to "本机存储")
            }
        }
        if(Build.VERSION.SDK_INT < 29) runCatching {
            @Suppress("DEPRECATION") val folder=java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"zmby")
            folder.listFiles()?.filter { it.isFile }?.forEach { found += Uri.fromFile(it) to (it.name to "本机存储") }
        }
        tree?.let { root -> runCatching {
            val folder = children(root, DocumentsContract.getTreeDocumentId(root)).firstOrNull { it.second == "zmby" }?.first
            if (folder != null) children(root, DocumentsContract.getDocumentId(folder)).forEach { found += it.first to (it.second to "U 盘") }
        } }
        return found.filter { it.second.first.endsWith(".zmby") || it.second.first.endsWith(".json") }.mapNotNull { (uri,info) ->
            runCatching {
                val text = read(uri)
                val header = if (text.trimStart().startsWith("[")) null else BackupCodec.header(text)
                Entry(uri, info.first, info.second, header?.first, header?.second == true)
            }.getOrNull()
        }.sortedByDescending { it.header?.createdAt ?: 0 }
    }

    private fun children(tree: Uri, id: String): List<Pair<Uri,String>> = buildList {
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,id), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null,null,null)?.use { c ->
            while (c.moveToNext()) add(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)) to c.getString(1))
        }
    }
}

private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if(n < 0) break; require(output.size()+n<=max) { "备份文件过大" }; output.write(buffer,0,n) }
    return output.toByteArray()
}
