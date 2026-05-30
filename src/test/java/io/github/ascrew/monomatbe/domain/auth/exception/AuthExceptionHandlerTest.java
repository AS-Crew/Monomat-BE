package io.github.ascrew.monomatbe.domain.auth.exception;

import io.github.ascrew.monomatbe.domain.auth.dto.AuthErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

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

    @Test
    void handleValidationException_unknownValidationCode_returnsInvalidRequestBodyWithBadRequest()
            throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(
                new TestAuthRequest("test"),
                "testAuthRequest"
        );

        bindingResult.addError(new FieldError(
                "testAuthRequest",
                "loginId",
                "test",
                false,
                null,
                null,
                "AUTH_LOGINID_REQUIRED"
        ));

        Method method = TestAuthController.class.getDeclaredMethod(
                "test",
                TestAuthRequest.class
        );

        MethodParameter methodParameter = new MethodParameter(method, 0);

        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                methodParameter,
                bindingResult
        );

        ResponseEntity<AuthErrorResponse> response =
                authExceptionHandler.handleValidationException(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(AuthErrorCode.AUTH_INVALID_REQUEST_BODY.name(), response.getBody().code());
        assertEquals(AuthErrorCode.AUTH_INVALID_REQUEST_BODY.getMessage(), response.getBody().message());
        assertEquals(AuthErrorCode.AUTH_INVALID_REQUEST_BODY.getField(), response.getBody().field());
    }

    @Test
    void handleValidationException_blankRefreshToken_returnsRefreshTokenRequiredWithBadRequest()
            throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(
                new TestRefreshTokenRequest(""),
                "testRefreshTokenRequest"
        );

        bindingResult.addError(new FieldError(
                "testRefreshTokenRequest",
                "refreshToken",
                "",
                false,
                null,
                null,
                "AUTH_REFRESH_TOKEN_REQUIRED"
        ));

        Method method = TestAuthController.class.getDeclaredMethod(
                "refresh",
                TestRefreshTokenRequest.class
        );

        MethodParameter methodParameter = new MethodParameter(method, 0);

        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                methodParameter,
                bindingResult
        );

        ResponseEntity<AuthErrorResponse> response =
                authExceptionHandler.handleValidationException(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(AuthErrorCode.AUTH_REFRESH_TOKEN_REQUIRED.name(), response.getBody().code());
        assertEquals(AuthErrorCode.AUTH_REFRESH_TOKEN_REQUIRED.getMessage(), response.getBody().message());
        assertEquals(AuthErrorCode.AUTH_REFRESH_TOKEN_REQUIRED.getField(), response.getBody().field());
    }

    private record TestAuthRequest(String loginId) {
    }

    private record TestRefreshTokenRequest(String refreshToken) {
    }

    private static final class TestAuthController {

        @SuppressWarnings("unused")
        void test(TestAuthRequest request) {
        }

        @SuppressWarnings("unused")
        void refresh(TestRefreshTokenRequest request) {
        }
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