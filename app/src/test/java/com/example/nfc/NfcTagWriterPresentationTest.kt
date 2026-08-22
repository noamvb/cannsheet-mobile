package com.example.nfc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the writer's tag-presentation gate. v1.6.0 shipped a writer that could not read any tag,
 * because the handler tore down reader mode before performing tag I/O and because the states that
 * ask for a retap did not accept one. These assertions pin the second half of that rule; the
 * first half is a code-review invariant documented on [acceptsTagPresentation].
 */
class NfcTagWriterPresentationTest {
    @Test
    fun everyStateThatAsksForAPresentationAcceptsOne() {
        // Each of these renders copy asking the owner to present or re-present a tag, so each must
        // process the callback it invited.
        assertTrue(acceptsTagPresentation(NfcTagWriterState.WaitingToInspect))
        assertTrue(acceptsTagPresentation(NfcTagWriterState.WaitingToWrite))
        assertTrue(acceptsTagPresentation(NfcTagWriterState.WaitingToVerify))
        assertTrue(acceptsTagPresentation(NfcTagWriterState.NeedsVerificationRetap))
    }

    @Test
    fun inFlightAndTerminalStatesIgnoreAPresentation() {
        // An operation already running must not be re-entered by a second callback...
        assertFalse(acceptsTagPresentation(NfcTagWriterState.Inspecting))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.Verifying))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.Writing))

        // ...and a settled outcome must not be silently restarted by a stray tap.
        assertFalse(acceptsTagPresentation(NfcTagWriterState.Editing))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.Verified))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.AlreadyVerified))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.CompatibleUnregistered))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.NoCompatibleTagToAdopt))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.RegistryMismatch))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.RegistryCorrupt))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.RegistrySaveFailed))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.ForeignContentFound))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.ReadOnlyIncompatible))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.Unsupported))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.TagLost))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.WriteUncertain))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.VerificationMismatch))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.InvalidConfiguration))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.UnavailableNoAdapter))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.NfcDisabled))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.LaunchPermissionBlocked))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.TooSmall(required = 40, available = 32)))
        assertFalse(acceptsTagPresentation(NfcTagWriterState.Failed("any")))
    }

    @Test
    fun resumingTheWriterPreservesAnyOutcomeTheOwnerStillHasToActOn() {
        // v1.6.1 reset these to WaitingToInspect on every resume, silently discarding the button
        // that was on screen.
        assertFalse(shouldArmWaitingState(NfcTagWriterState.CompatibleUnregistered))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.ForeignContentFound))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.RegistryMismatch))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.RegistrySaveFailed))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.NoCompatibleTagToAdopt))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.AlreadyVerified))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.Verified))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.RegistryCorrupt))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.InvalidConfiguration))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.ReadOnlyIncompatible))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.TooSmall(required = 40, available = 32)))
        assertFalse(shouldArmWaitingState(NfcTagWriterState.Writing))
    }

    @Test
    fun resumingArmsStatesThatCarryNoOutcome() {
        assertTrue(shouldArmWaitingState(NfcTagWriterState.Editing))
        assertTrue(shouldArmWaitingState(NfcTagWriterState.NfcDisabled))
        assertTrue(shouldArmWaitingState(NfcTagWriterState.UnavailableNoAdapter))
        assertTrue(shouldArmWaitingState(NfcTagWriterState.LaunchPermissionBlocked))
        // An operation interrupted by a pause has no result to preserve.
        assertTrue(shouldArmWaitingState(NfcTagWriterState.Inspecting))
        assertTrue(shouldArmWaitingState(NfcTagWriterState.Verifying))
        // Anything already awaiting a tap simply re-arms.
        assertTrue(shouldArmWaitingState(NfcTagWriterState.WaitingToInspect))
        assertTrue(shouldArmWaitingState(NfcTagWriterState.WaitingToWrite))
        assertTrue(shouldArmWaitingState(NfcTagWriterState.WaitingToVerify))
        assertTrue(shouldArmWaitingState(NfcTagWriterState.NeedsVerificationRetap))
    }
}
