-- Phase 0 · platform infrastructure (Database_Design §4.19).

-- Transactional outbox (spec §91): Spring Modulith event publication registry.
-- One row per (event, transactional listener), written in the same transaction as the
-- business change and completed when the listener succeeds; incomplete rows are
-- resubmitted by system.events.EventPublicationMaintenance.
-- Schema copied verbatim from spring-modulith-events-jdbc 2.2.0
-- (schemas/v2/schema-postgresql.sql); re-check it when upgrading Spring Modulith.
create table event_publication
(
  id                     uuid                     not null,
  listener_id            text                     not null,
  event_type             text                     not null,
  serialized_event       text                     not null,
  publication_date       timestamp with time zone not null,
  completion_date        timestamp with time zone,
  status                 text,
  completion_attempts    int,
  last_resubmission_date timestamp with time zone,
  primary key (id)
);
create index event_publication_serialized_event_hash_idx on event_publication using hash (serialized_event);
create index event_publication_by_completion_date_idx on event_publication (completion_date);

-- Cluster-wide locks for @Scheduled jobs (ShedLock, JdbcTemplateLockProvider with DB time).
create table shedlock
(
  name       varchar(64)  not null primary key,
  lock_until timestamptz  not null,
  locked_at  timestamptz  not null,
  locked_by  varchar(255) not null
);
