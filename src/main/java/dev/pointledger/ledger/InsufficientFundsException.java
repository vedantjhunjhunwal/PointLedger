package dev.pointledger.ledger;

public final class InsufficientFundsException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public InsufficientFundsException(String account, long balance, long requested) {
        super("insufficient points in " + account + ": balance " + balance + ", requested " + requested);
    }
}
