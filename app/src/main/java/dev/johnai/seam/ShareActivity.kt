package dev.johnai.seam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast

/**
 * Share target: Share to Seam sends text or files to the paired computer, then closes.
 * A text/plain share that is a single http(s) URL is sent as open_url; other text
 * still goes to the computer's clipboard (protocol/PROTOCOL.md, "Opening links").
 * Android will not let a background app read the clipboard, but a share intent can.
 * Files are described here (name and size via OpenableColumns). The link service opens an
 * InputStream with openInputStream or openTypedAssetFileDescriptor before onStartCommand
 * returns — that URI grant does not outlive the call — and streams chunks from it.
 * openFileDescriptor is not used: it fails for virtual documents (Drive, Gmail).
 * Only content:// URIs the caller listed in the intent data or ClipData with a read grant
 * are accepted. file:// is never opened. The sharing app is identified with
 * checkCallingUriPermission during an IPC, otherwise with getLaunchedFromUid and
 * checkUriPermission(pid = -1). A denial is a known caller, not an unknown one: unknown
 * fail-opens for a URI this app does not already hold. When the caller cannot be identified,
 * URIs this app can already open are refused.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val message = handle(intent)
            if (message != null) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        } finally {
            finish()
        }
    }

    private fun handle(intent: Intent?): String? {
        if (intent == null) return getString(R.string.clipboard_nothing)
        val files = fileShare(intent)
        if (files != null) {
            if (files.isEmpty()) return getString(R.string.file_nothing)
            if (LinkHub.status.state != LinkClient.State.CONNECTED) {
                return getString(R.string.clipboard_not_connected)
            }
            return handOff(files)
        }
        val text = sharedText(intent)
        if (text == null) {
            val type = intent.type?.substringBefore(';')?.trim()
            return if (intent.action == Intent.ACTION_SEND && !type.isNullOrEmpty() && type != "text/plain") {
                getString(R.string.file_nothing)
            } else {
                getString(R.string.clipboard_nothing)
            }
        }
        LinkHub.startService(this)
        // A single http(s) URL is opened on the computer; any other text stays on its clipboard.
        val url = LinkUrls.singleHttpUrl(text)
        val name = if (url != null) LinkHub.sendOpenUrl(this, url) else LinkHub.sendClipboard(this, text)
        return if (name == null) getString(R.string.clipboard_not_connected)
        else getString(R.string.clipboard_sent, name)
    }

    /** Null when this intent is not a file share, so text sharing can run. */
    private fun fileShare(intent: Intent): List<SharedFile>? {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            return acceptedStreams(intent).map { describe(it, intent.type) }
        }
        if (intent.action != Intent.ACTION_SEND) return null
        if (!hasStream(intent)) return null
        val uri = acceptedStreams(intent).firstOrNull() ?: return emptyList()
        return listOf(describe(uri, intent.type))
    }

    @Suppress("DEPRECATION")
    private fun hasStream(intent: Intent): Boolean {
        if (intent.data != null) return true
        if (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) != null) return true
        val clip = intent.clipData ?: return false
        for (i in 0 until clip.itemCount) {
            if (clip.getItemAt(i).uri != null) return true
        }
        return false
    }

    /**
     * Content URIs the caller actually granted. Extras are not a grant: a file:// in
     * EXTRA_STREAM is ignored even when the intent also carries FLAG_GRANT_READ_URI_PERMISSION.
     * If the extra is a granted clip URI, that order is kept; otherwise only clip/data grants.
     */
    @Suppress("DEPRECATION")
    private fun acceptedStreams(intent: Intent): List<Uri> {
        val granted = ArrayList<Uri>()
        fun consider(uri: Uri?) {
            if (uri == null || granted.any { it == uri }) return
            if (accepted(intent, uri)) granted.add(uri)
        }
        consider(intent.data)
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) consider(clip.getItemAt(i).uri)
        }
        if (granted.isEmpty()) return emptyList()
        val extras = ArrayList<Uri>()
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { extras.addAll(it) }
        if (extras.isEmpty()) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { extras.add(it) }
        }
        if (extras.isEmpty()) return granted
        val picked = extras.filter { extra -> granted.any { it == extra } }
        return if (picked.isNotEmpty()) picked else granted
    }

    private fun accepted(intent: Intent, uri: Uri): Boolean {
        val grantRead = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val read = callerRead(uri)
        val held = if (read.hasRead || read.known) false else alreadyHeld(uri)
        return FileSend.callerMayUpload(
            uri.scheme,
            uri.authority,
            grantRead,
            listedForGrant(intent, uri),
            packageName,
            read.hasRead,
            read.known,
            held,
        )
    }

    /**
     * Read access of the sharing app, never this process. checkCallingUriPermission is only
     * meaningful during an IPC; on the main thread it is not one, so a grant there would be
     * ours. The launching uid (API 34+) is then checked with pid -1. An identified uid that
     * cannot read is known: reporting it as unknown fail-opens through callerMayUpload.
     * A privileged uid is not proof, because the system can read every URI.
     */
    private fun callerRead(uri: Uri): FileSend.CallerAccess {
        val inIpc = Binder.getCallingPid() != Process.myPid()
        val ipcGranted = if (inIpc) {
            try {
                checkCallingUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        val uid = launchingUid()
        val identified = !inIpc &&
            uid != Process.INVALID_UID &&
            uid != Process.myUid() &&
            !isPrivilegedUid(uid)
        val uidCanRead = if (!identified) {
            false
        } else {
            try {
                checkUriPermission(
                    uri,
                    FileSend.CALLER_PID_UNKNOWN,
                    uid,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }
        return FileSend.callerAccess(
            inIpc = inIpc,
            ipcGranted = ipcGranted,
            launchedFromUid = uid,
            launchedUidCanRead = uidCanRead,
            myUid = Process.myUid(),
            invalidUid = Process.INVALID_UID,
            rootUid = Process.ROOT_UID,
            systemUid = Process.SYSTEM_UID,
        )
    }

    private fun launchingUid(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Process.INVALID_UID
        return try {
            launchedFromUid
        } catch (_: Exception) {
            Process.INVALID_UID
        }
    }

    private fun isPrivilegedUid(uid: Int): Boolean =
        uid == Process.SYSTEM_UID || uid == Process.ROOT_UID

    /**
     * Lasting access this app already has, independent of the share intent's grant.
     * Persisted grants and MediaStore owner are not enough: an exported ACTION_SEND can
     * name any content URI this process can already open (contacts, call log, a provider
     * whose read permission we hold).
     */
    private fun alreadyHeld(uri: Uri): Boolean {
        if (!FileSend.mayOpenSharedUri(uri.scheme, uri.authority, packageName)) return true
        if (isPersisted(uri)) return true
        if (ownedByUs(uri)) return true
        if (FileSend.authorityReadableByHeldPermission(
                uri.authority,
                holdsPermission(Manifest.permission.READ_CONTACTS),
                holdsPermission(Manifest.permission.READ_CALL_LOG),
                holdsPermission(Manifest.permission.READ_PHONE_STATE),
            )
        ) {
            return true
        }
        return providerReadableWithoutGrant(uri)
    }

    private fun holdsPermission(permission: String): Boolean = try {
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /**
     * True when the installed provider lets us read [uri] with no per-URI grant.
     * Unresolvable (package visibility) is not treated as held — the authority backstop
     * covers the providers our manifest permissions can already open. A resolve failure
     * is held, so we refuse rather than upload something we cannot classify.
     */
    private fun providerReadableWithoutGrant(uri: Uri): Boolean {
        val authority = uri.authority ?: return true
        val info = try {
            packageManager.resolveContentProvider(authority, 0)
        } catch (_: Exception) {
            return true
        } ?: return false
        if (info.packageName == packageName) return true
        val matching = ArrayList<String?>()
        val path = uri.path.orEmpty()
        val paths = info.pathPermissions
        if (paths != null) {
            for (pp in paths) {
                val matches = try {
                    pp.match(path)
                } catch (_: Exception) {
                    false
                }
                if (matches) matching.add(pp.readPermission)
            }
        }
        return FileSend.readableWithoutUriGrant(
            exported = info.exported,
            readPermission = info.readPermission,
            holds = { perm -> holdsPermission(perm) },
            matchingPathReadPermissions = matching,
        )
    }

    private fun isPersisted(uri: Uri): Boolean = try {
        contentResolver.persistedUriPermissions.any { perm ->
            perm.isReadPermission && perm.uri == uri
        }
    } catch (_: Exception) {
        false
    }

    private fun ownedByUs(uri: Uri): Boolean = try {
        contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            val column = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            if (column < 0) return false
            cursor.getString(column) == packageName
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun listedForGrant(intent: Intent, uri: Uri): Boolean {
        if (uri == intent.data) return true
        val clip = intent.clipData ?: return false
        for (i in 0 until clip.itemCount) {
            if (uri == clip.getItemAt(i).uri) return true
        }
        return false
    }

    private fun describe(uri: Uri, fallbackMime: String?): SharedFile {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameCol >= 0) {
                        cursor.getString(nameCol)?.takeIf { it.isNotEmpty() }?.let { name = it }
                    }
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)
                }
            }
        } catch (_: Exception) {
        }
        if (size < 0L) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                    if (fd.length >= 0L) size = fd.length
                }
            } catch (_: Exception) {
            }
        }
        val mime = try {
            contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        return SharedFile(
            uri,
            FileTransfer.sanitizeName(name),
            size,
            FileSend.usableMime(mime ?: fallbackMime),
        )
    }

    private fun handOff(files: List<SharedFile>): String? {
        return try {
            startForegroundService(FileOutbox.sendIntent(this, files))
            null
        } catch (_: Exception) {
            getString(R.string.clipboard_not_connected)
        }
    }

    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val type = intent.type?.substringBefore(';')?.trim() ?: return null
        if (type != "text/plain") return null
        val extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!extra.isNullOrEmpty()) return extra
        val item = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: item.coerceToText(this).toString().takeIf { it.isNotEmpty() }
    }

}
