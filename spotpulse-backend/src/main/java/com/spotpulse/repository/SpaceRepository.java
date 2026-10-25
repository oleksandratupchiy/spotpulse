package com.spotpulse.repository;

import com.spotpulse.model.Space;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpaceRepository extends JpaRepository<Space, Long> {

    @Query("select s.id from Space s order by s.id")
    List<Long> findSitemapIds(Pageable pageable);

    Page<Space> findByCity(String city, Pageable pageable);

    @Query("SELECT s FROM Space s WHERE " +
           "LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(s.city) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(s.address) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<Space> search(@Param("query") String query, Pageable pageable);
}
