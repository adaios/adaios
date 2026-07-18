package com.adaiadai.core.infrastructure.storage;

/**
 * StorageException — 文件存储层异常。
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
