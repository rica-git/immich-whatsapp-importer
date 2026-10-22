package com.example.whatsappmediatree

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ─── Data ───────────────────────────────────────────────────────────────────

data class LocalMedia(
    val id: String,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val type: String,
    var sha1Base64: String? = null
)

data class ReportItem(
    val name: String,
    val path: String = ""
)

data class SyncReport(
    val uploaded: MutableList<ReportItem> = mutableListOf(),
    val deleted: MutableList<ReportItem> = mutableListOf(),
    val skippedDuplicate: MutableList<ReportItem> = mutableListOf(),
    val errors: MutableList<ReportItem> = mutableListOf(),
    var tagName: String = "",
    var folderPath: String = ""
)

enum class Screen { HOME, FOLDER_BROWSER, SETTINGS, SYNC, REPORT }

// ─── Activity ───────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF25D366),
                    secondary = Color(0xFF128C7E),
                    tertiary = Color(0xFF075E54),
                    background = Color(0xFFF0F2F5)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("immich_sync", Context.MODE_PRIVATE) }

    var screen by remember { mutableStateOf(Screen.HOME) }
    var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var selectedFolder by remember { mutableStateOf<File?>(null) }
    var scannedMedia by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var scanRecursive by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(SyncReport()) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            selectedFolder = selectedFolder,
            mediaCount = scannedMedia.size,
            scanRecursive = scanRecursive,
            hasConfig = serverUrl.isNotBlank() && apiKey.isNotBlank(),
            onPickFolder = { screen = Screen.FOLDER_BROWSER },
            onSettings = { screen = Screen.SETTINGS },
            onSync = {
                if (scannedMedia.isNotEmpty()) screen = Screen.SYNC
            },
            onClearFolder = {
                selectedFolder = null
                scannedMedia = emptyList()
                scanRecursive = false
            }
        )
        Screen.FOLDER_BROWSER -> FolderBrowserScreen(
            onFolderSelected = { folder, media, recursive ->
                selectedFolder = folder
                scannedMedia = media
                scanRecursive = recursive
                screen = Screen.HOME
            },
            onCancel = { screen = Screen.HOME }
        )
        Screen.SETTINGS -> SettingsScreen(
            initialUrl = serverUrl,
            initialKey = apiKey,
            onSave = { url, key ->
                serverUrl = url.trim().trimEnd('/')
                apiKey = key.trim()
                prefs.edit()
                    .putString("server_url", serverUrl)
                    .putString("api_key", apiKey)
                    .apply()
                screen = Screen.HOME
            },
            onBack = { screen = Screen.HOME }
        )
        Screen.SYNC -> SyncScreen(
            mediaList = scannedMedia,
            folderPath = selectedFolder?.absolutePath ?: "",
            serverUrl = serverUrl,
            apiKey = apiKey,
            onFinished = { r ->
                report = r
                selectedFolder?.let { folder ->
                    scannedMedia = scanFolder(folder, recursive = scanRecursive)
                }
                screen = Screen.REPORT
            },
            onCancel = { screen = Screen.HOME }
        )
        Screen.REPORT -> ReportScreen(
            report = report,
            onDone = { screen = Screen.HOME }
        )
    }
}

// ─── Home ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    selectedFolder: File?,
    mediaCount: Int,
    scanRecursive: Boolean,
    hasConfig: Boolean,
    onPickFolder: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    onClearFolder: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(checkMediaPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermission = results.values.any { it } || checkMediaPermission(context)
    }
    val manageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasPermission = checkMediaPermission(context) }

    fun requestPerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                manageLauncher.launch(i)
            } catch (_: Exception) {
                val perms = if (Build.VERSION.SDK_INT >= 33)
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissionLauncher.launch(perms)
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("WhatsApp → Immich", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Folder Scan + Tags", fontSize = 12.sp, color = Color.White.copy(0.85f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF075E54),
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasPermission) {
                Card(colors = CardDefaults.cardColors(Color(0xFFFFF8E1))) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Storage permission required", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "To browse WhatsApp folders, please grant “All Files Access”.",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { requestPerms() },
                            colors = ButtonDefaults.buttonColors(Color(0xFF25D366))
                        ) { Text("Grant access") }
                    }
                }
            }

            // Folder selection
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFF25D366))
                        Spacer(Modifier.width(8.dp))
                        Text("Selected folder", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (selectedFolder == null) {
                        Text("No folder chosen yet.", fontSize = 14.sp, color = Color.Gray)
                    } else {
                        Text(
                            selectedFolder.absolutePath,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$mediaCount media file(s)" + if (scanRecursive) " (recursive scan)" else " (this directory only)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!hasPermission) requestPerms() else onPickFolder()
                            },
                            colors = ButtonDefaults.buttonColors(Color(0xFF25D366))
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (selectedFolder == null) "Choose folder" else "Change folder")
                        }
                        if (selectedFolder != null) {
                            OutlinedButton(onClick = onClearFolder) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            // Immich config status
            Card(
                colors = CardDefaults.cardColors(
                    if (hasConfig) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                )
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (hasConfig) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        null,
                        tint = if (hasConfig) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (hasConfig) "Immich configured" else "Configure Immich",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (hasConfig) "Tags will be applied on upload" else "URL + API Key",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    TextButton(onClick = onSettings) { Text("Configure") }
                }
            }

            // Sync button
            Button(
                onClick = onSync,
                enabled = hasPermission && hasConfig && selectedFolder != null && mediaCount > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(Color(0xFF25D366))
            ) {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text("Sync folder with Immich", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Text(
                "The scan processes **only** the chosen folder (not sibling folders). " +
                        "Each synchronization requires a **tag** name in Immich. " +
                        "Files that already exist on the server can be deleted locally.",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

// ─── Folder browser ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    onFolderSelected: (File, List<LocalMedia>, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val roots = remember {
        listOf(
            File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media"),
            File(Environment.getExternalStorageDirectory(), "WhatsApp/Media"),
            File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp")
        ).filter { it.exists() && it.canRead() }
    }

    var currentDir by remember {
        mutableStateOf(roots.firstOrNull() ?: Environment.getExternalStorageDirectory())
    }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var mediaInCurrent by remember { mutableStateOf(0) }
    var recursive by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        val dirs = currentDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        entries = dirs
        mediaInCurrent = currentDir.listFiles()?.count { f ->
            f.isFile && isMediaFile(f.name)
        } ?: 0
    }

    LaunchedEffect(currentDir) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Choose folder", fontWeight = FontWeight.Bold)
                        Text(
                            currentDir.name.ifBlank { currentDir.absolutePath },
                            fontSize = 12.sp,
                            color = Color.White.copy(0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = currentDir.parentFile
                        if (parent != null && (roots.any { currentDir.absolutePath.startsWith(it.absolutePath) } ||
                                    currentDir.absolutePath.startsWith(Environment.getExternalStorageDirectory().absolutePath))
                        ) {
                            // Don't go above storage root carelessly
                            if (parent.canRead()) currentDir = parent
                        } else {
                            onCancel()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF075E54),
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Current folder: ${currentDir.absolutePath}",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (recursive) "Recursive scan: includes subfolders"
                        else "$mediaInCurrent file(s) in this directory only",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Include subfolders (recursive)", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(
                            checked = recursive,
                            onCheckedChange = { recursive = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF25D366))
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            loading = true
                            scope.launch {
                                val media = withContext(Dispatchers.IO) {
                                    scanFolder(currentDir, recursive = recursive)
                                }
                                loading = false
                                onFolderSelected(currentDir, media, recursive)
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(Color(0xFF25D366))
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (recursive) "Use folder + subfolders" else "Use this directory only")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Quick jump to known roots
            if (roots.isNotEmpty()) {
                item {
                    Text(
                        "WhatsApp shortcuts",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
                items(roots) { root ->
                    ListItem(
                        headlineContent = { Text(root.name.ifBlank { "Media" }) },
                        supportingContent = { Text(root.absolutePath, fontSize = 11.sp, maxLines = 1) },
                        leadingContent = { Icon(Icons.Default.Home, null, tint = Color(0xFF25D366)) },
                        modifier = Modifier.clickable { currentDir = root }
                    )
                }
                item { HorizontalDivider() }
            }

            item {
                Text(
                    "Subfolders",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        "No subfolders here. You can use the current folder with the button below.",
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            } else {
                items(entries) { dir ->
                    val count = remember(dir) {
                        dir.listFiles()?.count { it.isFile && isMediaFile(it.name) } ?: 0
                    }
                    ListItem(
                        headlineContent = { Text(dir.name) },
                        supportingContent = {
                            Text(
                                if (count > 0) "$count media(s) at this level" else "folder",
                                fontSize = 12.sp
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Folder, null, tint = Color(0xFFFFB300)) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable { currentDir = dir }
                    )
                }
            }
        }
    }
}

// ─── Settings ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialUrl: String,
    initialKey: String,
    onSave: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Immich Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF075E54),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Immich server URL") },
                placeholder = { Text("https://immich.yourdomain.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Link, null) }
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Key, null) }
            )
            Text(
                "Recommended API Key permissions: asset.upload, asset.read, tag.create, tag.asset (or tag.*).",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Button(
                onClick = { onSave(url, key) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(Color(0xFF25D366)),
                enabled = url.isNotBlank() && key.isNotBlank()
            ) { Text("Save") }
        }
    }
}

// ─── Sync ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    mediaList: List<LocalMedia>,
    folderPath: String,
    serverUrl: String,
    apiKey: String,
    onFinished: (SyncReport) -> Unit,
    onCancel: () -> Unit
) {
    var step by remember { mutableStateOf("Waiting…") }
    var progress by remember { mutableStateOf(0f) }
    var tagName by remember { mutableStateOf("") }
    var showTagDialog by remember { mutableStateOf(true) }
    var log by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingDuplicates by remember { mutableStateOf<List<LocalMedia>>(emptyList()) }
    var reportHolder by remember { mutableStateOf(SyncReport()) }
    var deleteInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun addLog(msg: String) { log = log + msg }

    if (showTagDialog) {
        AlertDialog(
            onDismissRequest = { onCancel() },
            title = { Text("Immich Tag Name") },
            text = {
                Column {
                    Text("All files uploaded in this synchronization will receive this tag.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tagName,
                        onValueChange = { tagName = it },
                        label = { Text("Ex: whatsapp-sent-2026-10") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tagName.isNotBlank()) {
                            showTagDialog = false
                            running = true
                        }
                    },
                    enabled = tagName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(Color(0xFF25D366))
                ) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        )
    }

    var showDupList by remember { mutableStateOf(false) }

    // Confirm delete duplicates dialog
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { /* force explicit choice */ },
            title = { Text("Delete local duplicates?") },
            text = {
                Column {
                    Text(
                        "${pendingDuplicates.size} file(s) from this folder already exist in Immich.\n\n" +
                        "Do you want to delete local WhatsApp copies to free up space?"
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showDupList = !showDupList }) {
                        Text(
                            if (showDupList) "Hide list"
                            else "View ${pendingDuplicates.size} files"
                        )
                    }
                    if (showDupList) {
                        Column(
                            Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            pendingDuplicates.forEach { m ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    LocalThumb(path = m.path, size = 52.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(m.fileName, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        deleteInProgress = true
                        scope.launch {
                            step = "Deleting local duplicates…"
                            pendingDuplicates.forEach { m ->
                                val ok = withContext(Dispatchers.IO) { deleteLocalFile(m) }
                                if (ok) {
                                    reportHolder.deleted.add(ReportItem(m.fileName, m.path))
                                    addLog("🗑 ${m.fileName}")
                                } else {
                                    reportHolder.errors.add(ReportItem("Could not delete: ${m.fileName}", m.path))
                                }
                            }
                            deleteInProgress = false
                            progress = 1f
                            step = "Completed!"
                            delay(400)
                            onFinished(reportHolder)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(Color(0xFFE65100)),
                    enabled = !deleteInProgress
                ) { Text("Delete ${pendingDuplicates.size} files") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        addLog("Duplicates kept on device")
                        onFinished(reportHolder)
                    },
                    enabled = !deleteInProgress
                ) { Text("Keep local") }
            }
        )
    }

    // Actual sync work
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val report = SyncReport(tagName = tagName, folderPath = folderPath)
        try {
            val files = mediaList
            addLog("Folder: $folderPath")
            addLog("Files: ${files.size}")
            if (files.isEmpty()) {
                report.errors.add(ReportItem("No media in folder"))
                onFinished(report)
                return@LaunchedEffect
            }

            // 1. Hashes
            step = "Calculating SHA1…"
            val withHash = mutableListOf<LocalMedia>()
            files.forEachIndexed { i, m ->
                progress = 0.05f + 0.25f * (i + 1) / files.size
                step = "Hash ${i + 1}/${files.size}: ${m.fileName}"
                val hash = withContext(Dispatchers.IO) { computeSha1Base64(m) }
                if (hash != null) {
                    m.sha1Base64 = hash
                    withHash.add(m)
                } else {
                    report.errors.add(ReportItem("Hash failed: ${m.fileName}", m.path))
                }
            }
            addLog("Hashes OK: ${withHash.size}")

            // 2. Bulk check
            step = "Checking in Immich…"
            progress = 0.35f
            val checkResult = withContext(Dispatchers.IO) {
                immichBulkCheck(serverUrl, apiKey, withHash)
            }
            val toUpload = mutableListOf<LocalMedia>()
            val duplicates = mutableListOf<LocalMedia>()
            val existingAssetIds = mutableListOf<String>()
            withHash.forEach { m ->
                val cr = checkResult[m.id]
                if (cr?.action == "reject") {
                    duplicates.add(m)
                    report.skippedDuplicate.add(ReportItem(m.fileName, m.path))
                    cr.existingAssetId?.let { existingAssetIds.add(it) }
                } else {
                    toUpload.add(m)
                }
            }
            addLog("New: ${toUpload.size} | Duplicates: ${duplicates.size} (tag also on existing)")

            // 3. Create / get tag
            step = "Creating tag \"$tagName\"…"
            progress = 0.40f
            val tagId = withContext(Dispatchers.IO) {
                immichCreateTag(serverUrl, apiKey, tagName)
            }
            if (tagId == null) {
                addLog("ERROR: could not create/get tag \"$tagName\"")
                addLog("Check if API Key has tag.create / tag.read permissions")
                report.errors.add(ReportItem("Failed to create tag: $tagName"))
            } else {
                addLog("Tag ready: $tagName ($tagId)")
            }

            // 4. Upload
            val uploadedIds = mutableListOf<String>()
            toUpload.forEachIndexed { i, m ->
                progress = 0.40f + 0.50f * (i + 1) / toUpload.size.coerceAtLeast(1)
                step = "Upload ${i + 1}/${toUpload.size}: ${m.fileName}"
                val assetId = withContext(Dispatchers.IO) {
                    immichUpload(serverUrl, apiKey, m)
                }
                if (assetId != null) {
                    report.uploaded.add(ReportItem(m.fileName, m.path))
                    uploadedIds.add(assetId)
                    addLog("↑ ${m.fileName}")
                } else {
                    report.errors.add(ReportItem("Upload failed: ${m.fileName}", m.path))
                    addLog("✗ ${m.fileName}")
                }
            }

            // 5. Apply tag
            val idsToTag = (uploadedIds + existingAssetIds).distinct()
            if (tagId != null && idsToTag.isNotEmpty()) {
                step = "Applying tag (new + existing)…"
                progress = 0.92f
                val tagged = withContext(Dispatchers.IO) {
                    immichBulkTagAssets(serverUrl, apiKey, tagId, idsToTag)
                }
                if (tagged) {
                    addLog("Tag \"$tagName\" applied to ${idsToTag.size} asset(s) (${uploadedIds.size} new + ${existingAssetIds.size} already on server)")
                } else {
                    addLog("ERROR applying tag — check tag.asset permission")
                    report.errors.add(ReportItem("Failed to apply tag to assets"))
                }
            } else if (tagId == null && idsToTag.isNotEmpty()) {
                addLog("NO tag (tag creation failed) — ${idsToTag.size} asset(s) without tag")
                report.errors.add(ReportItem("Tag not applied: failed to create/get tag"))
            }

            // 6. Ask user before deleting local duplicates
            if (duplicates.isNotEmpty()) {
                step = "Waiting for confirmation to delete duplicates…"
                progress = 0.95f
                reportHolder = report
                pendingDuplicates = duplicates
                addLog("${duplicates.size} duplicate(s) — waiting for your choice")
                confirmDelete = true
                return@LaunchedEffect
            }

            progress = 1f
            step = "Completed!"
            delay(500)
            onFinished(report)
        } catch (e: Exception) {
            report.errors.add(ReportItem(e.message ?: "Error"))
            onFinished(report)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Synchronizing…") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF075E54),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(step, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(log.takeLast(50)) { line ->
                    Text(line, fontSize = 12.sp, color = Color.DarkGray)
                }
            }
        }
    }
}

// ─── Report ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(report: SyncReport, onDone: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF075E54),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (report.tagName.isNotBlank()) {
                Text("Tag: ${report.tagName}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            if (report.folderPath.isNotBlank()) {
                Text("Folder: ${report.folderPath}", fontSize = 12.sp, color = Color.Gray)
            }
            ReportSection("Uploaded", report.uploaded, Color(0xFF2E7D32))
            ReportSection("Deleted locally", report.deleted, Color(0xFFE65100))
            ReportSection("Already existed in Immich", report.skippedDuplicate, Color(0xFF1565C0))
            ReportSection("Errors", report.errors, Color(0xFFC62828))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(Color(0xFF25D366))
            ) { Text("Done") }
        }
    }
}

@Composable
fun LocalThumb(path: String, isVideo: Boolean = false, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val bitmap = remember(path) {
        if (path.isBlank()) null
        else try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, opts)
            var sample = 1
            val maxSide = 128
            while ((opts.outWidth / sample) > maxSide || (opts.outHeight / sample) > maxSide) {
                sample *= 2
            }
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts2)
        } catch (_: Exception) {
            null
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            if (isVideo || path.lowercase().endsWith(".mp4") || path.lowercase().endsWith(".3gp") ||
                path.lowercase().endsWith(".mkv") || path.lowercase().endsWith(".mov")
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Icon(
                if (isVideo) Icons.Default.Videocam else Icons.Default.Image,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

@Composable
fun ReportSection(title: String, items: List<ReportItem>, color: Color) {
    var expanded by remember { mutableStateOf(items.isNotEmpty() && items.size <= 12) }
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = items.isNotEmpty()) { expanded = !expanded }
            ) {
                Text(
                    "$title (${items.size})",
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
                if (items.isNotEmpty()) {
                    Text(
                        if (expanded) "Hide" else "View ${items.size} files",
                        fontSize = 12.sp,
                        color = Color(0xFF25D366)
                    )
                }
            }
            if (items.isEmpty()) {
                Text("— none —", fontSize = 13.sp, color = Color.Gray)
            } else if (expanded) {
                Spacer(Modifier.height(8.dp))
                items.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        if (item.path.isNotBlank()) {
                            LocalThumb(path = item.path, size = 52.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(item.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────────

fun checkMediaPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager() ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

fun isMediaFile(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".webp") || n.endsWith(".gif") ||
            n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv") || n.endsWith(".mov")
}

/**
 * Scan a directory for media files.
 * @param recursive if true, also scans all subdirectories.
 */
fun scanFolder(dir: File, recursive: Boolean = false): List<LocalMedia> {
    val out = mutableListOf<LocalMedia>()
    fun walk(d: File) {
        val files = d.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                if (recursive) walk(f)
            } else if (f.isFile && f.canRead() && isMediaFile(f.name)) {
                val n = f.name.lowercase()
                val type = if (
                    n.endsWith(".mp4") || n.endsWith(".3gp") ||
                    n.endsWith(".mkv") || n.endsWith(".mov")
                ) "video" else "image"
                out.add(
                    LocalMedia(
                        id = f.absolutePath.hashCode().toString(),
                        fileName = f.name,
                        path = f.absolutePath,
                        sizeBytes = f.length(),
                        timestamp = f.lastModified(),
                        type = type
                    )
                )
            }
        }
    }
    walk(dir)
    return out.sortedByDescending { it.timestamp }
}

fun computeSha1Base64(media: LocalMedia): String? {
    return try {
        val file = File(media.path)
        if (!file.exists()) return null
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
        }
        Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }
}

private val http = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .build()

data class BulkCheckItem(val action: String, val existingAssetId: String? = null)

fun immichBulkCheck(baseUrl: String, apiKey: String, media: List<LocalMedia>): Map<String, BulkCheckItem> {
    val result = mutableMapOf<String, BulkCheckItem>()
    media.chunked(50).forEach { chunk ->
        val assets = JSONArray()
        chunk.forEach { m ->
            assets.put(JSONObject().apply {
                put("id", m.id)
                put("checksum", m.sha1Base64)
            })
        }
        val body = JSONObject().put("assets", assets).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/api/assets/bulk-upload-check")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(body)
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@forEach
                val json = JSONObject(resp.body?.string() ?: return@forEach)
                val results = json.optJSONArray("results") ?: return@forEach
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val localId = r.optString("id")
                    val action = r.optString("action")
                    val existing = r.optString("assetId").ifBlank { null }
                    result[localId] = BulkCheckItem(action, existing)
                }
            }
        } catch (_: Exception) {}
    }
    media.forEach { if (!result.containsKey(it.id)) result[it.id] = BulkCheckItem("accept") }
    return result
}

fun immichCreateTag(baseUrl: String, apiKey: String, name: String): String? {
    try {
        val listReq = Request.Builder()
            .url("$baseUrl/api/tags")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(listReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: "[]"
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val n = t.optString("name")
                    val v = t.optString("value")
                    if (n.equals(name, true) || v.equals(name, true)) {
                        val id = t.optString("id")
                        if (id.isNotBlank()) return id
                    }
                }
            }
        }
    } catch (_: Exception) {}

    try {
        val body = JSONObject().put("name", name).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/api/tags")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                val id = JSONObject(text).optString("id")
                if (id.isNotBlank()) return id
            }
        }
    } catch (_: Exception) {}

    try {
        val upsertBody = JSONObject()
            .put("tags", JSONArray().put(name))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val upsertReq = Request.Builder()
            .url("$baseUrl/api/tags")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .put(upsertBody)
            .build()
        http.newCall(upsertReq).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                if (text.trimStart().startsWith("[")) {
                    val arr = JSONArray(text)
                    if (arr.length() > 0) {
                        val id = arr.getJSONObject(0).optString("id")
                        if (id.isNotBlank()) return id
                    }
                } else if (text.trimStart().startsWith("{")) {
                    val id = JSONObject(text).optString("id")
                    if (id.isNotBlank()) return id
                }
            }
        }
    } catch (_: Exception) {}

    try {
        val listReq = Request.Builder()
            .url("$baseUrl/api/tags")
            .header("x-api-key", apiKey)
            .get()
            .build()
        http.newCall(listReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val arr = JSONArray(resp.body?.string() ?: "[]")
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    if (t.optString("name").equals(name, true) || t.optString("value").equals(name, true)) {
                        val id = t.optString("id")
                        if (id.isNotBlank()) return id
                    }
                }
            }
        }
    } catch (_: Exception) {}

    return null
}

fun immichUpload(baseUrl: String, apiKey: String, media: LocalMedia): String? {
    val file = File(media.path)
    if (!file.exists()) return null
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val created = sdf.format(Date(media.timestamp))
    val modified = sdf.format(Date(file.lastModified()))

    val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("assetData", media.fileName, file.asRequestBody("application/octet-stream".toMediaType()))
        .addFormDataPart("deviceAssetId", media.id)
        .addFormDataPart("deviceId", "whatsapp-immich-sync")
        .addFormDataPart("fileCreatedAt", created)
        .addFormDataPart("fileModifiedAt", modified)
        .addFormDataPart("filename", media.fileName)
        .build()

    val reqBuilder = Request.Builder()
        .url("$baseUrl/api/assets")
        .header("x-api-key", apiKey)
        .header("Accept", "application/json")
        .post(multipart)
    media.sha1Base64?.let { reqBuilder.header("x-immich-checksum", it) }

    return try {
        http.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string() ?: return null).optString("id").takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }
}

fun immichBulkTagAssets(baseUrl: String, apiKey: String, tagId: String, assetIds: List<String>): Boolean {
    var ok = true
    assetIds.chunked(50).forEach { chunk ->
        val body = JSONObject()
            .put("tagIds", JSONArray().put(tagId))
            .put("assetIds", JSONArray(chunk))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/api/tags/assets")
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .put(body)
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body2 = JSONObject().put("ids", JSONArray(chunk)).toString()
                        .toRequestBody("application/json".toMediaType())
                    val req2 = Request.Builder()
                        .url("$baseUrl/api/tags/$tagId/assets")
                        .header("x-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .put(body2)
                        .build()
                    http.newCall(req2).execute().use { r2 ->
                        if (!r2.isSuccessful) ok = false
                    }
                }
            }
        } catch (_: Exception) {
            ok = false
        }
    }
    return ok
}

fun deleteLocalFile(media: LocalMedia): Boolean {
    return try {
        val f = File(media.path)
        f.exists() && f.delete()
    } catch (_: Exception) {
        false
    }
}