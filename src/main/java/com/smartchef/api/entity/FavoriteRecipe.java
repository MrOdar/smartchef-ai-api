package com.smartchef.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "favorite_recipes")
public class FavoriteRecipe {

    @EmbeddedId
    private FavoriteRecipeId id = new FavoriteRecipeId();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("recipeId")
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private Instant savedAt;

    protected FavoriteRecipe() {
    }

    public FavoriteRecipe(User user, Recipe recipe) {
        this.user = user;
        this.recipe = recipe;
        this.id = new FavoriteRecipeId(user.getId(), recipe.getId());
    }

    @PrePersist
    void onCreate() {
        if (savedAt == null) {
            savedAt = Instant.now();
        }
    }

    public FavoriteRecipeId getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Recipe getRecipe() {
        return recipe;
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    @Embeddable
    public static class FavoriteRecipeId implements Serializable {

        @Column(name = "user_id")
        private Long userId;

        @Column(name = "recipe_id")
        private Long recipeId;

        public FavoriteRecipeId() {
        }

        public FavoriteRecipeId(Long userId, Long recipeId) {
            this.userId = userId;
            this.recipeId = recipeId;
        }

        public Long getUserId() {
            return userId;
        }

        public Long getRecipeId() {
            return recipeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FavoriteRecipeId other)) {
                return false;
            }
            return Objects.equals(userId, other.userId) && Objects.equals(recipeId, other.recipeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, recipeId);
        }
    }
}
