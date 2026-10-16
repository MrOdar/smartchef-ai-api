package com.smartchef.api.repository;

import com.smartchef.api.entity.ScanSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScanSessionRepository extends JpaRepository<ScanSession, Long> {

    /**
     * Trae las sesiones con sus recetas en una sola consulta. Los ingredientes
     * de cada receta se cargan en lote (ver {@code @BatchSize} en la entidad):
     * no se pueden recuperar dos colecciones tipo bag en el mismo fetch join.
     */
    @Query("""
            select distinct s from ScanSession s
            left join fetch s.recipes
            where s.user.id = :userId
            order by s.createdAt desc
            """)
    List<ScanSession> findHistoryByUserId(@Param("userId") Long userId);

    @Query("""
            select distinct s from ScanSession s
            left join fetch s.recipes
            where s.id = :id
            """)
    Optional<ScanSession> findByIdWithRecipes(@Param("id") Long id);

    /**
     * Sesion de demostracion precalculada. Se compara con true explicitamente
     * porque las sesiones anteriores a la Fase 2 tienen la columna en NULL.
     */
    @Query("""
            select distinct s from ScanSession s
            left join fetch s.recipes
            where s.demo = true
            order by s.id asc
            """)
    List<ScanSession> findDemoSessions();
}
