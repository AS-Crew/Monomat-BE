CREATE TABLE game_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lobby_id BIGINT NOT NULL,
    current_round_no INT NOT NULL DEFAULT 1,
    total_round_count INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_game_session_lobby FOREIGN KEY (lobby_id) REFERENCES game_lobby(id) ON DELETE CASCADE
);

CREATE TABLE game_session_player (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    score INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_gsp_session FOREIGN KEY (game_session_id) REFERENCES game_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_gsp_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_gsp_session_user (game_session_id, user_id)
);
