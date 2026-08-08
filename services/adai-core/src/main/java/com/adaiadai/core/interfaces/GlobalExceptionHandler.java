package com.adaiadai.core.interfaces;

import com.adaiadai.core.domain.trading.TradingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理。
 * <p>
 * 业务异常（{@link TradingException} 等）映射为 4xx + 人类可读消息，
 * 避免静默 no-op 或 500 堆栈裸奔（REVIEW #147）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TradingException.class)
    public ResponseEntity<Map<String, String>> handleTradingException(TradingException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
