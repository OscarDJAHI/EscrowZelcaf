package com.zlecaf.escrow.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Legal B2B entity operating within the ZLECAf area.
 */
@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    /** National trade registry number. */
    @Column(name = "registration_number", unique = true, length = 100)
    private String registrationNumber;

    /** ISO-3166 alpha-3 country code (e.g. KEN, ZAF, NGA). */
    @Column(length = 3)
    private String country;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
