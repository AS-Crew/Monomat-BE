package io.github.ascrew.monomatbe.domain.map.entity;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Builder
@Table(name = "map")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class QuizMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "title", nullable = false, length = 50)
    private String title;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private MapCategory category;

    @Column(name = "num_of_song", nullable = false)
    private Integer numOfSong;

    @Column(name = "total_play_time", nullable = false)
    private Integer totalPlayTime;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic;

    @Column(name = "pending_public", nullable = false)
    private Boolean pendingPublic;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.numOfSong == null) {
            this.numOfSong = 0;
        }
        if (this.totalPlayTime == null) {
            this.totalPlayTime = 0;
        }
        if (this.isPublic == null) {
            this.isPublic = false;
        }
        if (this.pendingPublic == null) {
            this.pendingPublic = false;
        }
        if (this.isDeleted == null) {
            this.isDeleted = false;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void update(String title, String description, MapCategory category) {
        this.title = title;
        this.description = description;
        this.category = category;
    }

    public void softDelete() {
        this.isDeleted = true;
    }

    public void updateMetadata(int numOfSong, int totalPlayTime) {
        this.numOfSong = numOfSong;
        this.totalPlayTime = totalPlayTime;
    }

    public void markAsPublished() {
        this.isPublic = true;
        this.pendingPublic = false;
    }

    public void markAsUnpublished(boolean keepIntent) {
        this.isPublic = false;
        this.pendingPublic = keepIntent;
    }

    public void setPendingPublicIntent(boolean pending) {
        this.pendingPublic = pending;
    }
}
