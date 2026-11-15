-- One row holds the head of the audit hash chain. Appenders lock this row, which serialises the
-- chain without locking the whole table, and works both inside and outside a caller's transaction.
create table audit_chain_head (
    id         int primary key,
    last_hash  text not null,
    entries    bigint not null default 0
);

insert into audit_chain_head (id, last_hash, entries) values (1, 'GENESIS', 0);

-- Bring the head in line with any rows written before this migration.
update audit_chain_head
set last_hash = coalesce((select hash from audit_log order by seq desc limit 1), 'GENESIS'),
    entries = (select count(*) from audit_log)
where id = 1;
