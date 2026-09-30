package store.cadera.cdrbounty.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractApprovalStateTest {
    @Test
    void pendingApprovalCannotBeAcceptedOrClaimed() {
        assertFalse(ContractState.PENDING_APPROVAL.acceptingHunters());
        assertFalse(ContractState.PENDING_APPROVAL.claimable());
        assertFalse(ContractState.PENDING_APPROVAL.terminal());
    }

    @Test
    void rejectedIsTerminalAndNeverClaimable() {
        assertTrue(ContractState.REJECTED.terminal());
        assertFalse(ContractState.REJECTED.acceptingHunters());
        assertFalse(ContractState.REJECTED.claimable());
    }
}
