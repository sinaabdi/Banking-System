package com.sina.banking.data;

import java.util.Date;

public class Transaction {
    private Integer id;
    private Integer type;
    private Integer status;
    private String idempotencyKey;
    private Integer reversedTransactionId;
    private Date createdAt;
    private Date updatedAt;
}
