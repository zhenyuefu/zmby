package app.zmby.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.backup.*
import app.zmby.ui.*
import app.zmby.ui.components.*
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupScreen(nav: Nav) {
    val context = LocalContext.current
    val app = context.zmby
    val files = remember { BackupFiles(context) }
    val scope = rememberCoroutineScope()
    val servers by app.registry.servers.collectAsState()
    var options by remember { mutableStateOf(BackupOptions()) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var editingPassword by remember { mutableStateOf(false) }
    var destination by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var backups by remember { mutableStateOf<List<BackupFiles.Entry>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val usb = remember { context.getSystemService(StorageManager::class.java).storageVolumes.any { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED } }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) reload++ else message="需要存储权限才能保存到本机，也可以使用手机备份"
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { runCatching { files.grant(uri) }.onSuccess { reload++ }.onFailure { message = "无法使用这个目录，请重新选择" } }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { nav.go(Route.Restore(it.toString(),"文件")) } }
    LaunchedEffect(reload) { backups = withContext(Dispatchers.IO) { files.list() } }
    val passwordReady = password == confirm && (password.isEmpty() && !options.tokens || password.length >= 8)
    Column(Modifier.fillMaxSize().padding(horizontal = 80.dp,vertical = 56.dp),verticalArrangement = Arrangement.spacedBy(40.dp)) {
        Text("备份与恢复",fontSize = 48.sp,fontWeight = FontWeight.ExtraBold)
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f).groupCard(RoundedCornerShape(28.dp)).padding(30.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("导出",fontSize = 30.sp,fontWeight = FontWeight.Bold,modifier = Modifier.padding(22.dp,8.dp))
                ValueSetting("服务器与线路","必含")
                ToggleSetting("账号名",options.accounts,{ options = options.copy(accounts = !options.accounts) },modifier = Modifier.focusRequester(rememberInitialFocus(Unit)))
                ToggleSetting("登录令牌 · 加密",options.tokens,{ options = options.copy(tokens = !options.tokens) })
                ToggleSetting("每台服务器的设置",options.serverSettings,{ options = options.copy(serverSettings = !options.serverSettings) })
                ToggleSetting("应用设置",options.appSettings,{ options = options.copy(appSettings = !options.appSettings) })
                ValueSetting("备份密码",if(password.isEmpty()) if(options.tokens) "需要设置" else "未设置" else "已设置",onClick = { editingPassword = !editingPassword })
                if(editingPassword) {
                    Field("备份密码",password,{ password = it },KeyboardType.Password,secret = true)
                    Field("再输一次",confirm,{ confirm = it },KeyboardType.Password,secret = true,last = true,onSubmit = { if(passwordReady) editingPassword = false })
                    if(password != confirm && confirm.isNotEmpty()) Text("两次密码不一致",color = Z.warn)
                }
                ChoiceSetting("保存到",listOf("手机","U 盘","本机存储"),destination,{ if(it != 1 || usb || files.tree != null) destination = it },dim = if(!usb && files.tree == null) setOf(1) else emptySet())
                if(destination == 1) GlassButton("选择 U 盘目录",{ treePicker.launch(files.tree) },height = 60.dp,fontSize = 20)
                if(!passwordReady && destination != 0) Text("请设置至少 8 位的备份密码，并确认两次一致",fontSize = 18.sp,color = Z.warn,modifier = Modifier.padding(22.dp))
                if(destination == 0 || passwordReady) GlassButton(if(busy) "正在导出…" else "导出 ${servers.size} 台服务器",{
                    if(destination == 0) nav.go(Route.BackupImport)
                    else if(!busy) {
                        if(destination == 2 && Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else if(destination == 1 && files.tree == null) treePicker.launch(null)
                        else { busy = true; scope.launch {
                            try { val name = withContext(Dispatchers.IO) { files.save(app.backups.export(options,password),destination == 1) }; message = "已保存 · $name"; password = ""; confirm = ""; reload++ }
                            catch (_: Exception) { message = "导出失败，请检查密码和存储权限" }
                            finally { busy = false }
                        } }
                    }
                },Modifier.padding(8.dp,18.dp))
                message?.let { Text(it,fontSize = 19.sp,color = Z.textSecondary) }
            }
            Column(Modifier.weight(1f).groupCard(RoundedCornerShape(28.dp)).padding(30.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("恢复",fontSize = 30.sp,fontWeight = FontWeight.Bold,modifier = Modifier.padding(22.dp,8.dp))
                backups.forEach { file ->
                    ValueSetting(file.header?.let { dateLabel(it.createdAt) } ?: file.name,
                        "${file.header?.serverCount ?: "?"} 台服务器 · ${if(file.encrypted) "含加密内容" else "不含令牌"}",
                        sub = file.source,onClick = { nav.go(Route.Restore(file.uri.toString(),file.source)) })
                }
                if(backups.isEmpty()) Text("还没有备份文件",fontSize = 21.sp,color = Z.textSecondary,modifier = Modifier.padding(22.dp))
                ValueSetting("从手机或电脑上传","扫码",onClick = { nav.go(Route.BackupImport) })
                ValueSetting("选择备份文件","打开",onClick = { filePicker.launch(arrayOf("*/*")) })
                ValueSetting("选择 U 盘目录","浏览",onClick = { treePicker.launch(files.tree) })
            }
        }
    }
}

@Composable
fun RestoreScreen(nav: Nav, uri: String, source: String) {
    val context = LocalContext.current
    val app = context.zmby
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var filename by remember { mutableStateOf("备份文件") }
    var header by remember { mutableStateOf<BackupHeader?>(null) }
    var encrypted by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(RestoreMode.Merge) }
    var content by remember { mutableStateOf<BackupContent?>(null) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    LaunchedEffect(uri) { withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(Uri.parse(uri),arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) filename=c.getString(0) }
            text = BackupFiles(context).read(Uri.parse(uri)); if(!text!!.trimStart().startsWith("[")) BackupCodec.header(text!!).let { header = it.first; encrypted = it.second } }
        catch (_: Exception) { message = "备份无法读取或格式不支持" }
    } }
    LaunchedEffect(text,password) {
        content = null; plan = null; confirmReplace = false
        val file = text ?: return@LaunchedEffect
        if(encrypted && password.isEmpty()) return@LaunchedEffect
        delay(300)
        withContext(Dispatchers.IO) {
            try { val data = app.backups.decode(file,password); content = data; plan = app.backups.preview(data); message = null }
            catch (_: Exception) { message = if(encrypted) "密码不对或备份已损坏" else "备份内容无法识别" }
        }
    }
    Column(Modifier.fillMaxSize().padding(80.dp,56.dp),verticalArrangement = Arrangement.spacedBy(40.dp)) {
        Text("恢复备份",fontSize = 48.sp,fontWeight = FontWeight.ExtraBold)
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(620.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.fillMaxWidth().groupCard(RoundedCornerShape(28.dp)).padding(30.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(header?.let { dateLabel(it.createdAt) } ?: "备份文件",fontSize = 28.sp,fontWeight = FontWeight.Bold)
                    Text("$source · $filename",fontSize = 18.sp,color = Z.textSecondary,maxLines = 2)
                    Text("${header?.serverCount ?: content?.servers?.size ?: "?"} 台服务器",fontSize = 22.sp)
                    if(encrypted) Badge("加密备份")
                    header?.parts?.let { parts -> Text(listOfNotNull("服务器与线路", "账号名".takeIf { parts.accounts }, "登录令牌".takeIf { parts.tokens }, "服务器设置".takeIf { parts.serverSettings }, "应用设置".takeIf { parts.appSettings }).joinToString(" · "),fontSize=18.sp,color=Z.textSecondary) }
                }
                if(encrypted) Field("备份密码",password,{ password = it },KeyboardType.Password,secret = true,last = true,initialFocus = rememberInitialFocus(uri))
                Row(Modifier.fillMaxWidth(),verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("方式",fontSize=22.sp,modifier=Modifier.weight(1f))
                    SlidingChoice("合并到现有","全部替换",mode.ordinal,{ mode=RestoreMode.entries[it];confirmReplace=false })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for((title,count) in listOf("新增" to plan?.added,"更新线路" to plan?.updated,"相同跳过" to plan?.skipped)) Column(Modifier.weight(1f).groupCard(RoundedCornerShape(20.dp)).padding(20.dp)) {
                        Text((count ?: "—").toString(),fontSize = 34.sp,fontWeight = FontWeight.Bold); Text(title,fontSize = 18.sp,color = Z.textSecondary)
                    }
                }
                message?.let { Text(it,color = Z.warn,fontSize = 20.sp) }
                if(plan != null) GlassButton(if(busy) "正在验证并恢复…" else if(confirmReplace) "再次确认替换" else if(mode == RestoreMode.Replace) "替换为 ${plan!!.rows.size} 台" else "恢复 ${plan!!.rows.size} 台",{
                    if(!busy) {
                        if(mode == RestoreMode.Replace && !confirmReplace) confirmReplace = true
                        else { busy = true; scope.launch {
                            try { app.backups.restore(content!!,mode,confirmReplace); message = "恢复完成"; password = ""; nav.back() }
                            catch (_: Exception) { message = "恢复失败，请重试" }
                            finally { busy = false }
                        } }
                    }
                },if(!encrypted) Modifier.focusRequester(rememberInitialFocus(uri)) else Modifier)
                GlassButton("取消",nav::back)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(plan == null) Text("${header?.serverCount ?: ""} 台服务器 · 解锁后查看",fontSize = 24.sp,color = Z.textSecondary)
                plan?.rows?.forEach { row ->
                    Row(Modifier.fillMaxWidth().groupCard(RoundedCornerShape(22.dp)).padding(24.dp),horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.weight(1f)) { Text(row.incoming.config.name,fontSize = 24.sp,fontWeight = FontWeight.Bold); Text("${row.incoming.config.lines.size} 条线路",fontSize = 19.sp,color = Z.textSecondary) }
                        Badge(if(row.needsLogin) "需重新登录" else row.action,fill = row.action == "新增")
                    }
                }
            }
        }
    }
}

private fun dateLabel(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(time))
