package com.spotpulse.repository;

import com.spotpulse.model.Space;
import com.spotpulse.dto.SpaceSuggestionDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpaceRepository extends JpaRepository<Space, Long> {

    @Query("""
            select new com.spotpulse.dto.SpaceSuggestionDto(s.id, s.name, s.city, s.address)
            from Space s
            where lower(s.name) like :pattern escape '!' or lower(s.city) like :pattern escape '!'
            order by lower(s.name), lower(s.city), s.id
            """)
    List<SpaceSuggestionDto> autocomplete(@Param("pattern") String pattern, Pageable pageable);

    @Query("select s.id from Space s order by s.id")
    List<Long> findSitemapIds(Pageable pageable);

    Page<Space> findByCity(String city, Pageable pageable);

    @Query("SELECT s FROM Space s WHERE " +
           "LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(s.city) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(s.address) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<Space> search(@Param("query") String query, Pageable pageable);
}
