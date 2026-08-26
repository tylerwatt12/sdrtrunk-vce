/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

/** Winner, runner-up, and the first quality/tie-break field that selected the winner. */
public record LogicalCallDiagnosticWinner(String winnerLegId, String runnerUpLegId,
                                          LogicalCallWinnerCriterion criterion, CriterionValue winnerValue,
                                          CriterionValue runnerUpValue)
{
    public LogicalCallDiagnosticWinner
    {
        criterion = criterion != null ? criterion : LogicalCallWinnerCriterion.SINGLE_LEG;
        winnerValue = winnerValue != null ? winnerValue : CriterionValue.empty();
        runnerUpValue = runnerUpValue != null ? runnerUpValue : CriterionValue.empty();
    }

    /**
     * Exact comparable value. Count/rate criteria use numerator and denominator; stable text tie-breakers use only
     * display. This avoids lossy formatting while still giving a UI a ready human-readable value.
     */
    public record CriterionValue(String display, Long numerator, Long denominator)
    {
        public CriterionValue
        {
            if(numerator != null && denominator != null && denominator < 0L)
            {
                denominator = 0L;
            }
        }

        public static CriterionValue empty()
        {
            return new CriterionValue(null, null, null);
        }
    }
}
