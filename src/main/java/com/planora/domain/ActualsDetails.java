package com.planora.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Actuals by COA (chart-of-accounts / line name), year, property, organization.
 * Physical table: tbl_actuals_details (logical TBL_ACTUALS_DETAILS).
 */
@Entity
@Table(
        name = "tbl_actuals_details",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_actuals_coa_year_prop_org",
                columnNames = {"coa_code", "year", "property_id", "organization_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class ActualsDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coa_code", nullable = false, length = 255)
    private String coaCode;

    @Column(precision = 19, scale = 4)
    private BigDecimal janValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal febValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal marValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal aprValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal mayValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal junValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal julValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal augValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal sepValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal octValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal novValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal decValue;

    /** Daily values for the month (e.g. 28–31 numbers), stored as JSON array */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "daily_details")
    private List<BigDecimal> dailyDetails;

    @Column(nullable = false)
    private Integer year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId = 1L;
}
