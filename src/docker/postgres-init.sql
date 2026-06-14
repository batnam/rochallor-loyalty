-- Runs once on first Postgres startup (docker-entrypoint-initdb.d). The POSTGRES_DB
-- env var already creates loyalty_core; create the remaining per-service databases.
-- Each service owns its own schema via Flyway (spring.flyway), so the databases just
-- need to exist, empty, and owned by the shared 'loyalty' role.
CREATE DATABASE loyalty_earning    OWNER loyalty;
CREATE DATABASE loyalty_redemption OWNER loyalty;
CREATE DATABASE loyalty_campaign   OWNER loyalty;
-- NB: loyalty_core is NOT created here — POSTGRES_DB=loyalty_core already created it.
-- Re-creating it errors ("database already exists") and aborts Postgres init on a fresh volume.