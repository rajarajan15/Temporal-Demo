package com.occlet.issuance.api;

import com.occlet.issuance.contracts.TenantSettings;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The tenant registry and per-tenant settings. Settings are passed into workflows as input, so
 * workflows never read configuration themselves.
 */
@ConfigurationProperties("issuance")
record IssuanceProperties(String taskQueue, Map<String, Tenant> tenants, Webhooks webhooks) {

    record Tenant(Integer confirmationTimeoutSeconds) {}

    record Webhooks(String secret) {}

    boolean isTenant(String tenantId) {
        return tenantId != null && tenants.containsKey(tenantId);
    }

    TenantSettings settingsFor(String tenantId) {
        Tenant tenant = tenants.get(tenantId);
        return new TenantSettings(tenant == null ? null : tenant.confirmationTimeoutSeconds());
    }
}
