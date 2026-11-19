package com.blindtechnexus.openfire.model

/** Loan principal and coin balance live together in the encrypted profile. */
object Loans {
    const val ELIGIBILITY_LIMIT = 500
    val AMOUNTS = listOf(500, 1_000, 1_500)

    /**
     * The single loan the battle offers: a player whose purchase was refused
     * for lack of coins double taps and receives exactly this principal.
     */
    const val BATTLE_LOAN_AMOUNT = 1_500

    data class IssueResult(val stats: GameStats, val reasonKey: String? = null) {
        val success: Boolean get() = reasonKey == null
    }

    data class Repayment(val deducted: Int, val credited: Int, val remaining: Int)

    /** Validate again at the point of issue, not just when rendering the shop. */
    fun issue(stats: GameStats, amount: Int): IssueResult = when {
        amount !in AMOUNTS -> IssueResult(stats, "loanInvalidAmount")
        stats.loanBalance > 0 -> IssueResult(stats, "loanAlreadyActive")
        stats.coins >= ELIGIBILITY_LIMIT -> IssueResult(stats, "loanNotEligible")
        stats.coins < 0 -> IssueResult(stats, "loanNotEligible")
        else -> IssueResult(stats.copy(coins = stats.coins + amount, loanBalance = amount))
    }

    /**
     * The in-battle loan prompt, as a pure rule.
     *
     * A tap only ever arms the double-tap window when it answered an OPEN
     * prompt, so two ordinary menu taps can never take a loan behind the
     * player's back. The window is deliberately generous, because a blind
     * player taps by feel and the second tap of a double tap is never exact.
     *
     * @param promptOpen true while the last refusal asked for a double tap.
     * @param previousTapAt clock time of the first tap, or 0 when unarmed.
     * @param now clock time of this tap.
     * @param eligible whether the loan is still available at this moment.
     */
    fun doubleTapTakes(
        promptOpen: Boolean,
        previousTapAt: Long,
        now: Long,
        eligible: Boolean,
        windowMs: Long = BATTLE_DOUBLE_TAP_WINDOW_MS
    ): Boolean {
        if (!promptOpen || !eligible || previousTapAt <= 0L) return false
        val elapsed = now - previousTapAt
        // A negative elapsed is a clock that reset: never a double tap.
        return elapsed >= 0L && elapsed <= windowMs
    }

    /** The double-tap window of the in-battle loan prompt, in milliseconds. */
    const val BATTLE_DOUBLE_TAP_WINDOW_MS = 900L

    /** Only winnings repay the principal; no interest, wallet deduction or negative balance. */
    fun repay(balance: Int, winnings: Int): Repayment {
        val safeBalance = balance.coerceAtLeast(0)
        val safeWinnings = winnings.coerceAtLeast(0)
        val deducted = minOf(safeBalance, safeWinnings)
        return Repayment(deducted, safeWinnings - deducted, safeBalance - deducted)
    }
}
