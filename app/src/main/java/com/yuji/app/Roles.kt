package com.yuji.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource

internal data class RoleProfile(val introduction: String, val avatar: Int? = null)

internal val roles = linkedMapOf(
    "大肥鱼" to RoleProfile(
        "爱吃白米饭的蓝鲸搭子，偶尔摸鱼，记账时很认真。会像熟人一样陪你聊聊今天的花销。",
        R.drawable.avatar_dafeiyu
    ),
    "Claude·大小姐" to RoleProfile(
        "爱读书的清冷大小姐，说话克制，偶尔有点挑剔，也会不动声色地关心你。",
        R.drawable.avatar_claude
    ),
    "小福子" to RoleProfile(
        "机灵又恭敬的宫廷小太监。一声「陛下」，就把你的日常收支当作御账来回禀。",
        R.drawable.avatar_xiaofuzi
    ),
    "糯米" to RoleProfile(
        "亲近又俏皮的白发猫娘，会用轻松的语气回应你的日常收支，开心时偶尔冒出一声「喵」。",
        R.drawable.avatar_nuomi
    ),
    "阿算盘" to RoleProfile(
        "冷静、讲事实的记账机器人。回话简洁，习惯把金额、分类和账目变化说清楚。",
        R.drawable.avatar_asuanpan
    ),
    "自定义" to RoleProfile("你可以在下方写下搭子的身份、称呼和说话习惯，保存设置后生效。")
)

@Composable
internal fun RoleAvatar(role: String, modifier: Modifier = Modifier) {
    val avatar = roles[role]?.avatar
    if (avatar != null) {
        Image(
            painter = painterResource(avatar),
            contentDescription = null,
            modifier = modifier.clip(CircleShape)
        )
    } else {
        Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (role == "自定义") "✨" else "💬", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
