package com.sina.banking.data;

import java.util.Date;

public class LedgerEntry {
    private Integer id;
    private Integer transactionId;
    private Integer accountId;
    private Integer direction;
    private String currency;
    private Date createdAt;
}
