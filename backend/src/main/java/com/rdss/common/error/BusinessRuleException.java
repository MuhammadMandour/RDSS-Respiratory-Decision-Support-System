package com.rdss.common.error;

/** A semantically invalid request that passed syntactic validation. Mapped to 422. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
