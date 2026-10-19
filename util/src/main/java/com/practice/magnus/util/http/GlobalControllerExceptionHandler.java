package com.practice.magnus.util.http;

import com.practice.magnus.util.exceptions.InvalidInputException;
import com.practice.magnus.util.exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

import static org.springframework.http.HttpStatus.*;

@RestControllerAdvice
class GlobalControllerExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalControllerExceptionHandler.class);

    @ResponseStatus(NOT_FOUND)
    @ExceptionHandler(NotFoundException.class)
    public @ResponseBody
    HttpErrorInfo handleNotFoundExceptions(ServerHttpRequest request, Exception ex) {

        return createHttpErrorInfo(NOT_FOUND, request, ex);
    }

    @ResponseStatus(UNPROCESSABLE_CONTENT)
    @ExceptionHandler(InvalidInputException.class)
    public @ResponseBody HttpErrorInfo handleInvalidInputException(ServerHttpRequest request, Exception ex) {

        return createHttpErrorInfo(UNPROCESSABLE_CONTENT, request, ex);
    }

    /**
     * 요청 값을 메서드 파라미터로 변환하지 못했을 때 (예: /product/no-integer).
     * 컨트롤러 메서드가 호출되기 전에 발생하므로 서비스 코드에서는 처리할 수 없다.
     */
    @ResponseStatus(BAD_REQUEST)
    @ExceptionHandler(ServerWebInputException.class)
    public @ResponseBody HttpErrorInfo handleBadInputException(ServerHttpRequest request, ServerWebInputException ex) {

        String message = (ex.getCause() instanceof TypeMismatchException)
                ? "Type mismatch."
                : ex.getReason();

        return createHttpErrorInfo(BAD_REQUEST, request, message);
    }
    private HttpErrorInfo createHttpErrorInfo(HttpStatus httpStatus, ServerHttpRequest request, Exception ex) {
        return createHttpErrorInfo(httpStatus, request, ex.getMessage());
    }

    private HttpErrorInfo createHttpErrorInfo(HttpStatus httpStatus, ServerHttpRequest request, String message) {
        final String path = request.getPath().pathWithinApplication().value();

        LOG.debug("Returning HTTP status: {} for path: {}, message: {}", httpStatus, path, message);
        return new HttpErrorInfo(httpStatus, path, message);
    }
}