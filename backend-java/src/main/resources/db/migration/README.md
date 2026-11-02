# Flyway migrations

No migration files yet. The baseline schema must be derived from the final runtime
schema produced by the Go backend (`001_mysql_schema.sql` plus `ensureAccessSchema`
and `ensureCommerceV3Tables`), which is not available in this checkout.

Do not add a hand-written approximation here. `spring.flyway.baseline-on-migrate`
stays `false`; taking over an existing database is an explicit, reviewed step.
