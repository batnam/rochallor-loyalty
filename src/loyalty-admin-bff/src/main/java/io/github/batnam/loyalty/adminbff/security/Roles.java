package io.github.batnam.loyalty.adminbff.security;

/** Loyalty role vocabulary that gates BEP operations (Arch §7.1; loyalty-admin-bff.yaml). */
public final class Roles {

    private Roles() {
    }

    public static final String CS_MAKER = "loyalty-cs-maker";
    public static final String CS_CHECKER = "loyalty-cs-checker";
    public static final String CAMPAIGN_MANAGER = "loyalty-campaign-manager";
    public static final String FRAUD_OPS = "loyalty-fraud-ops";
    public static final String ADMIN = "loyalty-admin";
    public static final String READONLY = "loyalty-readonly";
}
