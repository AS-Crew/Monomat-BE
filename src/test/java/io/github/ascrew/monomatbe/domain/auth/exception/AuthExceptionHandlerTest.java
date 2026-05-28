package io.github.ascrew.monomatbe.domain.auth.exception;

import io.github.ascrew.monomatbe.domain.auth.dto.AuthErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuthExceptionHandlerTest {

    private final AuthExceptionHandler authExceptionHandler = new AuthExceptionHandler();

    @Test
    void handleHttpMessageNotReadableException_returnsInvalidRequestBodyWithBadRequest() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Malformed JSON",
                new TestHttpInputMessage()
        );

        ResponseEntity<AuthErrorResponse> response =
                authExceptionHandler.handleHttpMessageNotReadableException(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(AuthErrorCode.AUTH_INVALID_REQUEST_BODY.name(), response.getBody().code());
        assertEquals(AuthErrorCode.AUTH_INVALID_REQUEST_BODY.getMessage(), response.getBody().message());
        assertEquals(AuthErrorCode.AUTH_INVALID_REQUEST_BODY.getField(), response.getBody().field());
    }

    private static final class TestHttpInputMessage implements HttpInputMessage {

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream("{".getBytes());
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }
    }
}