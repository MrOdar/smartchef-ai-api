package com.smartchef.api.repository;

import com.smartchef.api.entity.Recipe;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    @Query("""
            select distinct r from Recipe r
            left join fetch r.ingredients
            where r.id = :id
            """)
    Optional<Recipe> findByIdWithIngredients(@Param("id") Long id);
}
