package org.apache.fineract.portfolio.savings.data;

public enum TransactionTypeEnum {

    CREDIT("CREDIT"), DEBIT("DEBIT");

    TransactionTypeEnum(final String type) {
        this.type = type;
    }

    private final String type;

    @Override
    public String toString() {
        return this.type;
    }
}