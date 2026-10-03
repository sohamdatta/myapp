package com.example.loginapp.core;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who is making the request. Built from the session row on every request, so
 * the Organization always comes from the server and never from the client.
 *
 * @param kind identity (signed in, no Organization chosen), tenant, platform or support
 */
public record SessionCtx(
        UUID sessionId,
        UUID identityId,
        String email,
        String kind,
        UUID tenantId,
        String tenantName,
        UUID membershipId,
        UUID grantId,
        String grantMode,
        String grantExpiresAt,
        UUID platformUserId,
        String platformRole,
        List<Perm> permissions) {

    public record Perm(String code, String scope) {}

    /** Never available in a support session, whatever the role rows say. */
    private static final Set<String> NEVER_UNDER_SUPPORT = Set.of(
            "payroll.approve", "payroll.release", "role.assign", "billing.manage", "tenant.manage");

    public boolean inOrganization() {
        return kind.equals("tenant") || kind.equals("support");
    }

    public boolean isSupport() {
        return kind.equals("support");
    }

    public boolean isPlatform() {
        return kind.equals("platform");
    }

    /** True when the permission is held at any scope. */
    public boolean can(String permission) {
        if (!inOrganization() || (isSupport() && NEVER_UNDER_SUPPORT.contains(permission))) {
            return false;
        }
        return permissions.stream().anyMatch(p -> p.code().equals(permission));
    }

    /** True when the permission is held across the whole Organization. */
    public boolean canAcrossOrganization(String permission) {
        if (!inOrganization() || (isSupport() && NEVER_UNDER_SUPPORT.contains(permission))) {
            return false;
        }
        return permissions.stream().anyMatch(p -> p.code().equals(permission) && p.scope().equals("tenant"));
    }

    /**
     * The widest scope at which the permission is held: tenant, assigned, team
     * or self, in that order. Null when it is not held at all.
     */
    public String scopeOf(String permission) {
        if (!inOrganization() || (isSupport() && NEVER_UNDER_SUPPORT.contains(permission))) {
            return null;
        }
        String widest = null;
        for (String scope : List.of("self", "team", "assigned", "tenant")) {
            if (permissions.stream().anyMatch(p -> p.code().equals(permission) && p.scope().equals(scope))) {
                widest = scope;
            }
        }
        return widest;
    }

    /** True when the permission reaches every user of the Organization (tenant, or assigned until entity scoping exists). */
    public boolean reachesEveryone(String permission) {
        String scope = scopeOf(permission);
        return "tenant".equals(scope) || "assigned".equals(scope);
    }

    public void require(String permission) {
        if (!inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
        if (!canAcrossOrganization(permission)) {
            throw ApiException.denied(permission);
        }
    }

    public void requirePlatform(String... roles) {
        if (!isPlatform() || platformRole == null || !List.of(roles).contains(platformRole)) {
            throw new ApiException(403, "permission.denied", "This needs a platform role: " + String.join(" or ", roles));
        }
    }

    public String actorKind() {
        return isSupport() ? "support" : "member";
    }
}
