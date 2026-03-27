package com.planora.web.dto;

import com.planora.enums.PlanInitMode;
import com.planora.enums.PlanType;
import jakarta.validation.constraints.NotNull;

public record CreatePlanRequest(
        @NotNull Long propertyId,
        @NotNull Integer fiscalYear,
        @NotNull PlanType planType,
        Long organizationId,
        /** When null, treated as {@link PlanInitMode#NONE}. */
        PlanInitMode initMode,
        /**
         * Required when {@code initMode == FROM_YEAR} (plan year to copy) or {@code initMode == FROM_ACTUALS}
         * (actuals calendar year).
         */
        Integer sourceYear,
        /** Required when {@code initMode == FROM_PLAN}: id of the plan to copy from. */
        Long sourcePlanId
) {}
