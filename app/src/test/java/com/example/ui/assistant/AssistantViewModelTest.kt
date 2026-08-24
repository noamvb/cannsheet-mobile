package com.example.ui.assistant

import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.SentenceCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AssistantViewModelTest {

    @Test
    fun testInitialUiStateDefaults() {
        val state = AssistantUiState(isAvailable = true)
        assertNotNull(state.currentThreadId)
        assertTrue(state.filterByCurrentApp)
        assertFalse(state.allowCrossApp)
        assertFalse(state.isGenerating)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.isAvailable)
    }

    @Test
    fun testChatMessageCreationAndCitations() {
        val citation = SentenceCitation(
            sentence = "You logged 21 entries across 14 active days.",
            citedFactIds = listOf("fact_cannsheet_1234", "fact_cannsheet_5678"),
        )
        val msg = ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = "You logged 21 entries across 14 active days.",
            status = AssistantTerminalStatus.VALIDATED,
            citations = listOf(citation),
        )

        assertEquals(MessageSender.ASSISTANT, msg.sender)
        assertEquals(AssistantTerminalStatus.VALIDATED, msg.status)
        assertEquals(1, msg.citations.size)
        assertEquals(2, msg.citations[0].citedFactIds.size)
    }

    @Test
    fun testValidationFailedChatMessage() {
        val msg = ChatMessage(
            sender = MessageSender.ASSISTANT,
            text = "Ungrounded text claim",
            status = AssistantTerminalStatus.FAILED_VALIDATION,
            validationIssues = listOf("Ungrounded number: 99"),
        )

        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, msg.status)
        assertEquals(1, msg.validationIssues.size)
        assertEquals("Ungrounded number: 99", msg.validationIssues[0])
    }
}
