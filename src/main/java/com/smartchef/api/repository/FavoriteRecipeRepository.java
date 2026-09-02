package com.smartchef.api.repository;

import com.smartchef.api.entity.FavoriteRecipe;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRecipeRepository
        extends JpaRepository<FavoriteRecipe, FavoriteRecipe.FavoriteRecipeId> {

    @Query("""
            select f from FavoriteRecipe f
            where f.user.id = :userId and f.recipe.id = :recipeId
            """)
    Optional<FavoriteRecipe> findByUserAndRecipe(@Param("userId") Long userId,
                                                 @Param("recipeId") Long recipeId);

    @Query("""
            select distinct f from FavoriteRecipe f
            join fetch f.recipe r
            left join fetch r.ingredients
            where f.user.id = :userId
            order by f.savedAt desc
            """)
    List<FavoriteRecipe> findAllByUserIdWithRecipes(@Param("userId") Long userId);
}
