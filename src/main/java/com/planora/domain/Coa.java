package com.planora.domain;

import com.planora.enums.LineItemType;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Chart of accounts: code, display name, department + {@link LineItemType}, scoped by property and organization.
 * Aligns with {@link com.planora.domain.PlanMonthlyDetails} line_key / department / type when building plan rows.
 */
@Entity
@Table(
        name = "tbl_coa",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_coa_code_prop_org",
                columnNames = {"coa_code", "property_id", "organization_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class Coa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coa_code", nullable = false, length = 128)
    private String coaCode;

    /** Human-readable label (commas and special characters allowed; stored in DB / Excel, not in line_key matching). */
    @Column(name = "coa_name", nullable = false, length = 512)
    private String coaName;

    @Column(nullable = false, length = 255)
    private String department;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_item_type", nullable = false, length = 32)
    private LineItemType lineItemType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId = 1L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
