package com.musicstreaming.common.exception;

public class StorageLimitExceededException extends RuntimeException {

    public StorageLimitExceededException(String message) {
        super(message);
    }
}
