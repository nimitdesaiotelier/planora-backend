package com.planora.enums;

/**
 * How to populate month values when creating a plan: empty rows, or copy from another plan.
 */
public enum PlanInitMode {
    NONE,
    /** Same property and plan type, fiscal year = new plan year − 1. */
    LAST_YEAR,
    /** Same property and plan type, fiscal year = {@code sourceYear}. */
    FROM_YEAR,
    /** Copy values from any active plan on the same property (matched by line key). */
    FROM_PLAN,
    /** Copy month values from uploaded actuals for {@code sourceYear} (same property & COA). */
    FROM_ACTUALS
}
