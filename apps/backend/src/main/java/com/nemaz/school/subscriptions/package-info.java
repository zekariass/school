/**
 * Subscription plans, entitlements and the manual school subscription of P1
 * (Database_Design §4.20). Billing arrives in P5. Other modules use
 * {@link com.nemaz.school.subscriptions.SubscriptionService} and its value types.
 */
@ApplicationModule(displayName = "Subscriptions")
package com.nemaz.school.subscriptions;

import org.springframework.modulith.ApplicationModule;
