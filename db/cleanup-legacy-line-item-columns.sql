-- Optional one-time cleanup after Hibernate has created jan_value..dec_value and daily_details.
-- ddl-auto=update does not drop removed mappings; run against PostgreSQL when ready.
-- If you still have data only in period_values / ly_actuals, migrate those columns into the new shape before dropping.
ALTER TABLE tbl_plan_monthly_details DROP COLUMN IF EXISTS period_values;
ALTER TABLE tbl_plan_monthly_details DROP COLUMN IF EXISTS ly_actuals;
