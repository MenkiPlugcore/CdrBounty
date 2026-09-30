package store.cadera.cdrbounty.contract;

public enum ContractState {
    DRAFT,
    OPEN,
    RESERVED,
    CLAIMING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
    VOIDED;

    public boolean acceptingHunters() {
        return this == OPEN || this == RESERVED;
    }

    public boolean claimable() {
        return this == OPEN || this == RESERVED;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED || this == CANCELLED || this == VOIDED;
    }
}
