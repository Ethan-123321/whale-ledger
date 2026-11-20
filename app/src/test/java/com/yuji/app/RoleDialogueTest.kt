package com.yuji.app

import org.junit.Assert.*
import org.junit.Test

class RoleDialogueTest {
    private val source = ChatMessage(text = "主要是力量，有氧太累了。", role = "大肥鱼", user = true)
    private val earlier = source.copy(id = "earlier", text = "你平时练力量还是有氧？", user = false)

    @Test fun takesRecentQuestionsAndSimpleEntriesIntoAccount() {
        assertTrue(RoleDialogue.leaveSpace(source, listOf(earlier), simpleEntry = false))
        assertTrue(RoleDialogue.leaveSpace(source, emptyList(), simpleEntry = true))
        assertFalse(RoleDialogue.leaveSpace(source, listOf(earlier.copy(role = "小福子")), simpleEntry = false))
        assertFalse(RoleDialogue.leaveSpace(source.copy(text = "考考我"), listOf(earlier), simpleEntry = false))
    }

    @Test fun dropsAppendedQuestionsWhenTheReplyAlreadyStandsOnItsOwn() {
        assertEquals("有氧一开场就要命，本鱼懂。", RoleDialogue.finish("有氧一开场就要命，本鱼懂。你练腿吗？深蹲还是硬拉？", true))
        assertEquals("这笔是收入还是支出？", RoleDialogue.finish("这笔是收入还是支出？", true))
        val open = "本鱼懂。你今天有力气练吗？"
        assertEquals(open, RoleDialogue.finish(open, false))
    }
}
