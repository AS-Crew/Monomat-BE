package io.github.ascrew.monomatbe.domain.game.exception;

public class GameSessionAlreadyExistsException extends RuntimeException {
    public GameSessionAlreadyExistsException(String message) {
        super(message);
    }
}
