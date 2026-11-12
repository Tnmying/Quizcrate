package com.offlinequiz.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.offlinequiz.app.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DefaultNickname = "题匣"
private const val DefaultSignature = "每一次练习，都有积累"
private const val NicknameLimit = 24
private const val SignatureLimit = 80
private const val AvatarSize = 512

private enum class ProfileField(val title: String, val limit: Int) {
    NICKNAME("昵称", NicknameLimit),
    SIGNATURE("个性签名", SignatureLimit)
}

@Composable
internal fun ProfileHeader() {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("profile", Context.MODE_PRIVATE) }
    val avatarFile = remember(context) { AtomicFile(File(context.filesDir, "profile-avatar.png")) }
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf(preferences.getString("nickname", DefaultNickname) ?: DefaultNickname) }
    var signature by remember { mutableStateOf(preferences.getString("signature", DefaultSignature) ?: DefaultSignature) }
    var avatar by remember { mutableStateOf<Bitmap?>(null) }
    var editingField by rememberSaveable { mutableStateOf<ProfileField?>(null) }
    var editingAvatar by rememberSaveable { mutableStateOf(false) }
    var avatarBusy by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(avatarFile) {
        avatar = withContext(Dispatchers.IO) {
            try {
                avatarFile.openRead().use { BitmapFactory.decodeStream(it) }
            } catch (_: IOException) {
                null
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            avatarBusy = true
            avatarError = null
            scope.launch {
                try {
                    avatar = withContext(Dispatchers.IO) { saveAvatar(context, uri, avatarFile) }
                    editingAvatar = false
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    avatarError = "无法保存这张图片，请换一张试试。"
                } finally {
                    avatarBusy = false
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(role = Role.Button, onClickLabel = "更换头像") {
                    avatarError = null
                    editingAvatar = true
                }
        ) {
            ProfileAvatar(avatar, Modifier.fillMaxSize(), "头像")
        }
        Column(Modifier.weight(1f)) {
            Text(
                nickname,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = "修改昵称") { editingField = ProfileField.NICKNAME }
                    .padding(vertical = 6.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                signature.ifEmpty { "添加个性签名" },
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = "修改个性签名") { editingField = ProfileField.SIGNATURE }
                    .padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    editingField?.let { field ->
        EditProfileDialog(
            field = field,
            currentValue = if (field == ProfileField.NICKNAME) nickname else signature,
            onDismiss = { editingField = null }
        ) { value ->
            if (field == ProfileField.NICKNAME) {
                nickname = value
                preferences.edit().putString("nickname", value).apply()
            } else {
                signature = value
                preferences.edit().putString("signature", value).apply()
            }
            editingField = null
        }
    }
    if (editingAvatar) {
        ProfileDialog("更换头像", onDismiss = { if (!avatarBusy) editingAvatar = false }) {
            ProfileAvatar(
                avatar,
                Modifier.align(Alignment.CenterHorizontally).size(80.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                "当前头像"
            )
            avatarError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            GlassActionButton(
                onClick = { pickPhoto.launch("image/*") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !avatarBusy
            ) { Text(if (avatarBusy) "正在保存…" else "从手机选择图片") }
            if (avatar != null) {
                GlassActionButton(
                    onClick = {
                        avatarBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    avatarFile.delete()
                                    if (avatarFile.baseFile.exists()) throw IOException("Avatar deletion failed")
                                }
                                avatar = null
                                editingAvatar = false
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: IOException) {
                                avatarError = "无法恢复默认头像，请重试。"
                            } finally {
                                avatarBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !avatarBusy
                ) { Text("恢复默认头像") }
            }
            GlassActionButton(
                onClick = { editingAvatar = false },
                modifier = Modifier.align(Alignment.End),
                enabled = !avatarBusy
            ) { Text("取消") }
        }
    }
}

@Composable
private fun ProfileAvatar(bitmap: Bitmap?, modifier: Modifier, description: String) {
    if (bitmap == null) {
        Image(painterResource(R.drawable.mascot_profile), description, modifier, contentScale = ContentScale.Crop)
    } else {
        Image(bitmap.asImageBitmap(), description, modifier, contentScale = ContentScale.Crop)
    }
}

@Composable
private fun EditProfileDialog(
    field: ProfileField,
    currentValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by rememberSaveable(field) { mutableStateOf(currentValue) }
    val isNickname = field == ProfileField.NICKNAME
    val canSave = !isNickname || draft.isNotBlank()
    ProfileDialog("修改${field.title}", onDismiss) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        TextField(
            value = draft,
            onValueChange = { input ->
                val value = if (isNickname) input.replace(Regex("[\\r\\n]+"), " ") else input
                if (value.length <= field.limit) draft = value
            },
            modifier = Modifier.fillMaxWidth().glassSurface(18.dp).focusRequester(focus),
            placeholder = { Text("输入${field.title}") },
            singleLine = isNickname,
            minLines = if (isNickname) 1 else 3,
            maxLines = if (isNickname) 1 else 4,
            keyboardOptions = KeyboardOptions(imeAction = if (isNickname) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(draft.trim()) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        Text(
            "${draft.length} / ${field.limit}",
            modifier = Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            GlassActionButton(onClick = onDismiss) { Text("取消") }
            WarmButton(onClick = { onSave(draft.trim()) }, enabled = canSave) { Text("保存") }
        }
    }
}

@Composable
private fun ProfileDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        GlassPanel(Modifier.clip(RoundedCornerShape(GlassCardRadius)).glassBackdrop()) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

private fun saveAvatar(context: Context, uri: Uri, target: AtomicFile): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val longest = maxOf(width, height)
        if (longest > AvatarSize) {
            decoder.setTargetSize(
                (width.toLong() * AvatarSize / longest).toInt().coerceAtLeast(1),
                (height.toLong() * AvatarSize / longest).toInt().coerceAtLeast(1)
            )
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val stream = target.startWrite()
    try {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Avatar encoding failed")
        target.finishWrite(stream)
    } catch (error: Exception) {
        target.failWrite(stream)
        throw error
    }
    return bitmap
}
