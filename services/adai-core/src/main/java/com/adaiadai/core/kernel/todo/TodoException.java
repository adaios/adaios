package com.adaiadai.core.kernel.todo;

/**
 * TodoException — 待办业务异常（RFC 20260917）。
 * <p>
 * 违反待办规则时抛出（如内容为空、待办不存在），由 {@code interfaces.GlobalExceptionHandler}
 * 映射为 400 + 人类可读消息（fail-visible，不静默 no-op）。
 */
public class TodoException extends RuntimeException {

    public TodoException(String message) {
        super(message);
    }
}
