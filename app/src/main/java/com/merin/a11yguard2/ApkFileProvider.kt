package com.merin.a11yguard2

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 极简 FileProvider：只用来把「下载好的更新 APK」交给系统安装器读取。
 *
 * ── 为什么自己写而不引 androidx.core ──
 *
 * androidx 的 FileProvider 功能完整但会引入整个 androidx.core 依赖（约 1.5MB）。
 * 本应用只需要一个能力：把 getExternalFilesDir 下的一个 .apk 以 content:// 形式
 * 暴露给系统安装器（安装器没有权限读我们的私有目录）。
 *
 * 自定义实现的约束（安全相关，不要放宽）：
 *   1. **只暴露 APK**：URI 必须以 .apk 结尾，其它一律拒绝；
 *   2. **只暴露一个目录**：getExternalFilesDir(null) 之下，禁止路径穿越（..）；
 *   3. **只读**：openFile 只接受 "r" 模式，不支持写入；
 *   4. exported=false，只能通过 FLAG_GRANT_READ_URI_PERMISSION 临时授权访问。
 */
class ApkFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY_SUFFIX = ".fileprovider"

        fun authority(ctx: Context): String = ctx.packageName + AUTHORITY_SUFFIX

        /** 为一个 APK 文件构造 content:// URI。 */
        fun uriFor(ctx: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(ctx))
                .appendPath(file.name)
                .build()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    /** 把 URI 解析成受限制的文件路径；任何不合规都返回 null。 */
    private fun resolve(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        // 1) 只允许 .apk
        if (!name.endsWith(".apk", ignoreCase = true)) return null
        // 2) 禁止路径穿越与目录分隔
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null

        val base = ctx.getExternalFilesDir(null) ?: return null
        val f = File(base, name)
        // 3) 规范化后必须仍在 base 之下
        val canonicalBase = base.canonicalFile
        val canonicalFile = f.canonicalFile
        if (!canonicalFile.path.startsWith(canonicalBase.path + File.separator)) return null
        if (!canonicalFile.isFile) return null
        return canonicalFile
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        // 4) 只读
        if (!mode.startsWith("r")) return null
        val f = resolve(uri) ?: return null
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            null
        }
    }

    /** 让安装器能拿到文件名与大小（部分 ROM 需要）。 */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(cols.size)
        cols.forEachIndexed { i, c ->
            row[i] = when (c) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
