package com.mtk.keyu

import android.media.projection.MediaProjection
import androidx.compose.runtime.mutableStateListOf
import java.io.File

/** 悬浮助手攒着的多条意见：每条 = 截图(可选) + 文字。变更自动同步悬浮球角标。 */
object Outbox {
    data class Item(val id: Long, val shot: File?, var note: String)
    val items = mutableStateListOf<Item>()
    private var nextId = 1L
    @Volatile var projection: MediaProjection? = null

    fun add(shot: File?, note: String) {
        items.add(Item(nextId++, shot, note))
        AppState.notifyBubble()
    }

    fun remove(id: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx].shot?.delete()
            items.removeAt(idx)
            AppState.notifyBubble()
        }
    }

    fun clear() {
        items.forEach { it.shot?.delete() }
        items.clear()
        AppState.notifyBubble()
    }
}
