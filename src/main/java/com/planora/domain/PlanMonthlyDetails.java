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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "tbl_plan_monthly_details",
        uniqueConstraints = @UniqueConstraint(columnNames = {"plan_id", "line_key"})
)
@Getter
@Setter
@NoArgsConstructor
public class PlanMonthlyDetails {

    public static final List<String> MONTH_KEYS = List.of(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(name = "coa_id")
    private Long coaId;

    @Column(name = "coa_code", length = 128)
    private String coaCode;

    @Column(name = "coa_name", length = 512)
    private String coaName;

    @Column(name = "line_key", nullable = false, length = 128)
    private String lineKey;

    @Column(nullable = false, length = 128)
    private String department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LineItemType type;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false)
    private String label;

    @Column(name = "jan_value", nullable = false)
    private Integer janValue = 0;

    @Column(name = "feb_value", nullable = false)
    private Integer febValue = 0;

    @Column(name = "mar_value", nullable = false)
    private Integer marValue = 0;

    @Column(name = "apr_value", nullable = false)
    private Integer aprValue = 0;

    @Column(name = "may_value", nullable = false)
    private Integer mayValue = 0;

    @Column(name = "jun_value", nullable = false)
    private Integer junValue = 0;

    @Column(name = "jul_value", nullable = false)
    private Integer julValue = 0;

    @Column(name = "aug_value", nullable = false)
    private Integer augValue = 0;

    @Column(name = "sep_value", nullable = false)
    private Integer sepValue = 0;

    @Column(name = "oct_value", nullable = false)
    private Integer octValue = 0;

    @Column(name = "nov_value", nullable = false)
    private Integer novValue = 0;

    @Column(name = "dec_value", nullable = false)
    private Integer decValue = 0;

    /**
     * Per-month daily amounts (28–31 values per key). Keys align with {@link #MONTH_KEYS}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "daily_details", nullable = false)
    private Map<String, List<Integer>> dailyDetails = new LinkedHashMap<>();

    /** Build API map Jan–Dec for plan totals. */
    public Map<String, Integer> toValuesMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Jan", janValue);
        m.put("Feb", febValue);
        m.put("Mar", marValue);
        m.put("Apr", aprValue);
        m.put("May", mayValue);
        m.put("Jun", junValue);
        m.put("Jul", julValue);
        m.put("Aug", augValue);
        m.put("Sep", sepValue);
        m.put("Oct", octValue);
        m.put("Nov", novValue);
        m.put("Dec", decValue);
        return m;
    }

    public void applyValuesMap(Map<String, Integer> values) {
        if (values == null) {
            return;
        }
        if (values.containsKey("Jan")) {
            janValue = zeroIfNull(values.get("Jan"));
        }
        if (values.containsKey("Feb")) {
            febValue = zeroIfNull(values.get("Feb"));
        }
        if (values.containsKey("Mar")) {
            marValue = zeroIfNull(values.get("Mar"));
        }
        if (values.containsKey("Apr")) {
            aprValue = zeroIfNull(values.get("Apr"));
        }
        if (values.containsKey("May")) {
            mayValue = zeroIfNull(values.get("May"));
        }
        if (values.containsKey("Jun")) {
            junValue = zeroIfNull(values.get("Jun"));
        }
        if (values.containsKey("Jul")) {
            julValue = zeroIfNull(values.get("Jul"));
        }
        if (values.containsKey("Aug")) {
            augValue = zeroIfNull(values.get("Aug"));
        }
        if (values.containsKey("Sep")) {
            sepValue = zeroIfNull(values.get("Sep"));
        }
        if (values.containsKey("Oct")) {
            octValue = zeroIfNull(values.get("Oct"));
        }
        if (values.containsKey("Nov")) {
            novValue = zeroIfNull(values.get("Nov"));
        }
        if (values.containsKey("Dec")) {
            decValue = zeroIfNull(values.get("Dec"));
        }
    }

    private static int zeroIfNull(Integer v) {
        return v == null ? 0 : v;
    }

    public void applyDailyDetailsMap(Map<String, List<Integer>> details) {
        if (details == null) {
            return;
        }
        Map<String, List<Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : details.entrySet()) {
            copy.put(e.getKey(), e.getValue() == null ? List.of() : new ArrayList<>(e.getValue()));
        }
        dailyDetails = copy;
    }
}
