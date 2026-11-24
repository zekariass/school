package com.nemaz.school.subscriptions;

import jakarta.validation.constraints.NotNull;

/** A plan as shown in the platform console. */
public record PlanInfo(@NotNull String code, @NotNull String name) {
}
