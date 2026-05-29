package io.github.ascrew.monomatbe.domain.map.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Builder
@Table(
        name = "map_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_map_item_active_order",
                columnNames = {"map_id", "active_order_num"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MapItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "map_id", nullable = false)
    private QuizMap map;

    @Column(name = "order_num", nullable = false)
    private Integer orderNum;

    @Column(name = "youtube_url", nullable = false, length = 500)
    private String youtubeUrl;

    @Column(name = "video_id", nullable = false, length = 20)
    private String videoId;

    @Column(name = "start_time", nullable = false)
    private Integer startTime;

    @Column(name = "end_time", nullable = false)
    private Integer endTime;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "artist", length = 255)
    private String artist;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "answer", nullable = false, length = 255)
    private String answer;

    @Column(name = "alt_answers", columnDefinition = "TEXT")
    private String altAnswers;

    @Column(name = "hint", nullable = false, length = 255)
    private String hint;

    @Column(name = "hint_time", nullable = false)
    private Integer hintTime;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // MySQL이 active 행에 대해서만 order_num을 채워주는 generated 컬럼.
    // (map_id, active_order_num) UNIQUE 제약과 결합해 동시 INSERT race를 DB 레벨에서 차단한다.
    @Column(
            name = "active_order_num",
            insertable = false,
            updatable = false,
            columnDefinition = "INT GENERATED ALWAYS AS (CASE WHEN is_deleted = FALSE THEN order_num ELSE NULL END) STORED"
    )
    private Integer activeOrderNum;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.hintTime == null) {
            this.hintTime = 15;
        }
        if (this.isDeleted == null) {
            this.isDeleted = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void update(
            int orderNum,
            String youtubeUrl,
            String videoId,
            int startTime,
            int endTime,
            String title,
            String artist,
            String thumbnailUrl,
            String answer,
            String altAnswers,
            String hint,
            int hintTime
    ) {
        this.orderNum = orderNum;
        this.youtubeUrl = youtubeUrl;
        this.videoId = videoId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.title = title;
        this.artist = artist;
        this.thumbnailUrl = thumbnailUrl;
        this.answer = answer;
        this.altAnswers = altAnswers;
        this.hint = hint;
        this.hintTime = hintTime;
    }

    public void reorder(int newOrderNum) {
        this.orderNum = newOrderNum;
    }

    public void softDelete() {
        this.isDeleted = true;
    }
}
