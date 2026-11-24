-- M1b · audit (Database_Design §4.19): append-only audit log, partitioned by month on
-- occurred_at. The application never updates or deletes rows (there is a single database
-- role, so UPDATE/DELETE cannot be revoked from the application yet; see the M1b as-built
-- note in docs/Implementation_Plan.md). audit.application.AuditPartitionMaintenance keeps
-- the current month and the next three months created.

create table audit_logs (
  id                   bigint generated always as identity,
  occurred_at          timestamptz not null default now(),
  actor_user_id        uuid,
  actor_roles          text[],
  impersonator_user_id uuid,
  school_id            uuid,
  action               text not null,             -- auth.otp.verify, school.create, role_assignment.grant ...
  target_type          text,
  target_id            uuid,
  outcome              text not null check (outcome in ('SUCCESS', 'DENIED', 'FAILURE')),
  request_id           text,
  ip                   inet,
  user_agent           text,
  changes              jsonb,                     -- {before, after} for mutations
  metadata             jsonb,
  primary key (id, occurred_at)
) partition by range (occurred_at);

create index ix_audit_logs_time on audit_logs (occurred_at desc, id desc);
create index ix_audit_logs_actor on audit_logs (actor_user_id, occurred_at desc) where actor_user_id is not null;
create index ix_audit_logs_target on audit_logs (target_type, target_id, occurred_at desc) where target_id is not null;
create index ix_audit_logs_school on audit_logs (school_id, occurred_at desc) where school_id is not null;
create index ix_audit_logs_action on audit_logs (action text_pattern_ops, occurred_at desc);

-- Safety net: a row outside every monthly partition must not fail the business transaction
-- that writes it. The maintenance job keeps months ahead, so this stays empty.
create table audit_logs_default partition of audit_logs default;

-- Current month (UTC) and the next three.
do $$
declare
  first_month date := date_trunc('month', now() at time zone 'UTC')::date;
  m date;
begin
  for i in 0..3 loop
    m := (first_month + make_interval(months => i))::date;
    execute format('create table if not exists %I partition of audit_logs for values from (%L) to (%L)',
                   'audit_logs_y' || to_char(m, 'YYYY') || 'm' || to_char(m, 'MM'),
                   m::timestamp at time zone 'UTC',
                   (m + interval '1 month')::timestamp at time zone 'UTC');
  end loop;
end
$$;
