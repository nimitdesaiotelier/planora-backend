package com.planora.service;

public class DuplicateActivePlanException extends RuntimeException {

    private final Long existingPlanId;

    public DuplicateActivePlanException(String message, Long existingPlanId) {
        super(message);
        this.existingPlanId = existingPlanId;
    }

    public Long getExistingPlanId() {
        return existingPlanId;
    }
}
