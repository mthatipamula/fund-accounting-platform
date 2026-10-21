package com.fundaccounting.engine.ledger;

/** Chart of accounts. Monetary accounts can hold foreign-currency balances that are revalued daily. */
public enum Account {
    CASH("1000", "Cash", Type.ASSET, true),
    INVESTMENTS_AT_COST("1100", "Investments at cost", Type.ASSET, false),
    UNREALIZED_APPRECIATION("1150", "Unrealized appreciation on investments", Type.ASSET, false),
    INTEREST_RECEIVABLE("1200", "Interest receivable", Type.ASSET, true),
    DIVIDENDS_RECEIVABLE("1210", "Dividends receivable", Type.ASSET, true),
    RECEIVABLE_SECURITIES_SOLD("1300", "Receivable for securities sold", Type.ASSET, true),
    PAYABLE_SECURITIES_PURCHASED("2100", "Payable for securities purchased", Type.LIABILITY, true),
    MANAGEMENT_FEES_PAYABLE("2200", "Management fees payable", Type.LIABILITY, false),
    CAPITAL_SUBSCRIPTIONS("3000", "Capital subscriptions", Type.EQUITY, false),
    CAPITAL_REDEMPTIONS("3100", "Capital redemptions", Type.EQUITY, false),
    REALIZED_GAIN_LOSS("4000", "Realized gain/loss on investments", Type.INCOME, false),
    CHANGE_IN_UNREALIZED("4100", "Change in unrealized appreciation", Type.INCOME, false),
    FX_GAIN_LOSS("4200", "Foreign exchange gain/loss", Type.INCOME, false),
    DIVIDEND_INCOME("4300", "Dividend income", Type.INCOME, false),
    INTEREST_INCOME("4310", "Interest income", Type.INCOME, false),
    MANAGEMENT_FEE_EXPENSE("5000", "Management fee expense", Type.EXPENSE, false);

    public enum Type { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }

    public final String code;
    public final String label;
    public final Type type;
    public final boolean monetary;

    Account(String code, String label, Type type, boolean monetary) {
        this.code = code;
        this.label = label;
        this.type = type;
        this.monetary = monetary;
    }

    /** Debit-normal accounts (assets, expenses) increase with debits. */
    public boolean debitNormal() { return type == Type.ASSET || type == Type.EXPENSE; }
}
