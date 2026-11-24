package com.occlet.issuance.contracts;

/** Per-tenant settings passed into a workflow as input, never read from inside it. Fields are optional. */
public record TenantSettings(Integer confirmationTimeoutSeconds) {}
