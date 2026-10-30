package com.spotpulse.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "spaces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Space {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @jakarta.validation.constraints.DecimalMin("-90")
    @jakarta.validation.constraints.DecimalMax("90")
    private Double latitude;

    @jakarta.validation.constraints.DecimalMin("-180")
    @jakarta.validation.constraints.DecimalMax("180")
    private Double longitude;

    /** URL-посилання на зображення простору (вимога C1) */
    private String imageUrl;

    // Additive, nullable server-owned metadata. Legacy/external URLs need no migration.
    @Column(length = 20)
    private String imageStorageProvider;
    @Column(length = 200)
    private String imageStorageKey;
    @Column(length = 128)
    private String imageAssetId;

    @Column(length = 254)
    private String contactEmail;

    // Legacy spaces remain unassigned until an administrator explicitly assigns them.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    // Portable H2/PostgreSQL default also backfills existing rows during ddl-auto=update.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean premium;

    @OneToMany(mappedBy = "space", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Room> rooms = new ArrayList<>();
}
