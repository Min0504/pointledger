package com.pointledger.ledger;

/**
 * 원장 엔트리 종류. amount는 항상 양수이고 방향(+/-)은 여기서 결정된다 —
 * 부호가 두 곳(amount, type)에 존재하면 불일치 버그가 생기므로 한 곳에 고정한다.
 */
public enum LedgerEntryType {
    EARN(+1),
    REDEEM(-1),
    CANCEL(+1),        // 사용 취소 = 복원
    EXPIRE(-1),
    ADMIN_GRANT(+1),
    ADMIN_REVOKE(-1);

    private final int sign;

    LedgerEntryType(int sign) {
        this.sign = sign;
    }

    /** 잔액에 반영할 부호 있는 금액 */
    public long signed(long amount) {
        return sign * amount;
    }
}
