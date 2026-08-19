package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NarrativeState.toCardBody] and [terminalState] hold every rule for whether the card
 * draws nothing, a loading body, or text, so they are asserted directly rather than
 * through Compose.
 */
class InsightNarrativeCardTest {

    @Test
    fun `hidden and failed states draw nothing`() {
        assertEquals(NarrativeCardBody.None, NarrativeState.Hidden.toCardBody())
        assertEquals(NarrativeCardBody.None, NarrativeState.Failed.toCardBody())
    }

    @Test
    fun `loading state draws the loading body`() {
        assertEquals(NarrativeCardBody.Loading, NarrativeState.Loading.toCardBody())
    }

    @Test
    fun `streaming and complete states draw their text`() {
        assertEquals(
            NarrativeCardBody.Text("Some words so far"),
            NarrativeState.Streaming("Some words so far").toCardBody(),
        )
        assertEquals(
            NarrativeCardBody.Text("The finished summary"),
            NarrativeState.Complete("The finished summary").toCardBody(),
        )
    }

    @Test
    fun `a blank finished summary draws nothing, same as a failure`() {
        assertEquals(NarrativeCardBody.None, NarrativeState.Complete("").toCardBody())
        assertEquals(NarrativeCardBody.None, NarrativeState.Complete("  \n").toCardBody())
    }

    @Test
    fun `a blank first fragment keeps the loading body rather than tearing the card down`() {
        assertEquals(NarrativeCardBody.Loading, NarrativeState.Streaming("").toCardBody())
        assertEquals(NarrativeCardBody.Loading, NarrativeState.Streaming("   ").toCardBody())
        assertEquals(NarrativeCardBody.Loading, NarrativeState.Streaming("\n").toCardBody())
    }

    /**
     * The loading state must never survive the end of a generation. A generation that
     * produces nothing at all is the case that matters: the card has to go back to
     * drawing nothing rather than leaving a progress bar on screen indefinitely.
     */
    @Test
    fun `a generation that produced nothing settles on hidden, not loading`() {
        assertEquals(NarrativeState.Hidden, terminalState(NarrativeState.Loading, ""))
        assertEquals(NarrativeState.Hidden, terminalState(NarrativeState.Loading, "   \n"))
        assertEquals(NarrativeCardBody.None, terminalState(NarrativeState.Loading, "").toCardBody())
    }

    @Test
    fun `a generation that produced text settles on complete`() {
        assertEquals(
            NarrativeState.Complete("The finished summary"),
            terminalState(NarrativeState.Streaming("The finished summary"), "The finished summary"),
        )
        assertEquals(
            NarrativeState.Complete("Trimmed"),
            terminalState(NarrativeState.Loading, "  Trimmed\n"),
        )
    }

    @Test
    fun `a failure is preserved and never promoted to a summary`() {
        assertEquals(
            NarrativeState.Failed,
            terminalState(NarrativeState.Failed, "partial text that arrived first"),
        )
        assertEquals(
            NarrativeCardBody.None,
            terminalState(NarrativeState.Failed, "partial").toCardBody(),
        )
    }
}
