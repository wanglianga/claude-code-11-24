package cc.royalty.domain;

public record Payable(Long id, Long settlementBatchId, Long payeeId, String rightType,
                      String currency, long amountMinor, long paidMinor, String status) {
    public long outstanding() {
        return amountMinor - paidMinor;
    }
}
