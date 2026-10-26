/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.transition

/**
 * Decides which deck owns the output and when an armed handoff takes effect. It neither decodes nor
 * mixes: the caller reads [activeDeck] and [armedIncomingDeck], asks [EqualPowerEnvelope] or
 * [TwoDeckMixer] for gains, and reports transport commands here. Keeping every commit and cancel in one
 * place is what lets a seek, pause or stop during a fade resolve the same way every time.
 */
class TwoDeckTransitionScheduler(initialDeck: TransitionDeck = TransitionDeck.FIRST) {
    var activeDeck: TransitionDeck = initialDeck
        private set

    private var armed: ArmedTransition? = null

    val armedIncomingDeck: TransitionDeck?
        get() = armed?.incoming

    val armedPlan: TransitionPlan?
        get() = armed?.plan

    /**
     * Arms [plan] to hand the output to [incoming]. Refused while another handoff is armed or when
     * [incoming] already owns the output, so a second request cannot silently replace a fade in progress.
     */
    fun arm(incoming: TransitionDeck, plan: TransitionPlan): Boolean {
        if (armed != null || incoming == activeDeck) return false
        armed = ArmedTransition(incoming, plan)
        return true
    }

    /**
     * Gains at [frame]. Without an armed handoff the active deck plays at unity and nothing else is audible.
     */
    fun gainsAt(frame: Long): TransitionGains {
        val transition = armed ?: return TransitionGains(outgoing = 1f, incoming = 0f)
        return EqualPowerEnvelope.gainsAt(transition.plan, frame)
    }

    /**
     * Commits the handoff once the timeline reaches the end of the overlap. Returns true only on the call
     * that makes the incoming deck active.
     */
    fun advanceTo(frame: Long): Boolean {
        val transition = armed ?: return false
        if (frame < transition.plan.endFrame) return false
        commit(transition)
        return true
    }

    /** A seek, pause or stop during a fade: the incoming deck becomes active at once. */
    fun commitIncoming(): Boolean {
        val transition = armed ?: return false
        commit(transition)
        return true
    }

    /** Drops the armed handoff. The outgoing deck keeps the output and nothing is promoted. */
    fun cancel() {
        armed = null
    }

    private fun commit(transition: ArmedTransition) {
        activeDeck = transition.incoming
        armed = null
    }

    private class ArmedTransition(
        val incoming: TransitionDeck,
        val plan: TransitionPlan,
    )
}
