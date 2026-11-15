-- AgentPayGate gateway schema. Everything is simulated money in minor units (bigint).

create table issuer_keys (
    key_id          text primary key,
    algorithm       text not null,
    public_key_der  bytea not null,
    created_at      timestamptz not null default now()
);

create table agents (
    agent_id      text primary key,
    display_name  text not null,
    api_key_hash  text not null unique,
    created_at    timestamptz not null default now()
);

create table mandates (
    mandate_id           text primary key,
    agent_id             text not null references agents (agent_id),
    issuer_key_id        text not null references issuer_keys (key_id),
    currency             text not null,
    max_per_transaction  bigint not null,
    max_total            bigint not null,
    max_per_day          bigint not null,
    allowed_merchants    text not null,
    allowed_categories   text not null,
    not_before           bigint not null,
    expires_at           bigint not null,
    nonce                text not null,
    signature            text not null,
    created_at           timestamptz not null default now()
);

-- Budget state per mandate. Written only through the row-locked spend repository.
create table mandate_spend (
    mandate_id          text primary key references mandates (mandate_id),
    spent_minor         bigint not null default 0,
    reserved_minor      bigint not null default 0,
    spent_today_minor   bigint not null default 0,
    reserved_today_minor bigint not null default 0,
    day_key             bigint not null,
    version             bigint not null default 0
);

create table payments (
    payment_id          text primary key,
    agent_id            text not null references agents (agent_id),
    mandate_id          text not null references mandates (mandate_id),
    merchant_id         text not null,
    merchant_category   text not null,
    currency            text not null,
    amount_minor        bigint not null,
    state               text not null,
    captured_minor      bigint not null default 0,
    refunded_minor      bigint not null default 0,
    provider_ref        text,
    provider_idem_key   text not null unique,
    reason_code         text,
    request_nonce       text not null,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create table payment_events (
    seq           bigserial primary key,
    payment_id    text not null references payments (payment_id),
    event_id      text not null,
    type          text not null,
    amount_minor  bigint not null default 0,
    provider_ref  text,
    reason_code   text,
    occurred_at   timestamptz not null default now(),
    unique (payment_id, event_id)
);

create table idempotency_keys (
    agent_id      text not null,
    idem_key      text not null,
    payload_hash  text not null,
    payment_id    text not null,
    state         text not null,
    created_at    timestamptz not null default now(),
    primary key (agent_id, idem_key)
);

-- Double-entry postings. The primary key on (business_event_id, posting_index) is what makes a
-- business event post at most once, even if the posting code runs twice.
create table ledger_postings (
    seq                bigserial primary key,
    business_event_id  text not null,
    posting_index      int not null,
    account_type       text not null,
    account_ref        text not null,
    amount_minor       bigint not null,
    currency           text not null,
    created_at         timestamptz not null default now(),
    unique (business_event_id, posting_index)
);

create table outbox (
    id                 bigserial primary key,
    payment_id         text not null references payments (payment_id),
    operation          text not null,
    provider_idem_key  text not null,
    payload            jsonb not null,
    status             text not null default 'PENDING',
    attempts           int not null default 0,
    next_attempt_at    timestamptz not null default now(),
    last_error         text,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

create index outbox_pending_idx on outbox (status, next_attempt_at);

create table webhook_inbox (
    event_id      text primary key,
    provider_ref  text not null,
    kind          text not null,
    amount_minor  bigint not null,
    payload       jsonb not null,
    received_at   timestamptz not null default now()
);

create table audit_log (
    seq         bigint generated always as identity primary key,
    actor       text not null,
    action      text not null,
    subject     text not null,
    detail      text not null,
    prev_hash   text not null,
    hash        text not null,
    created_at  timestamptz not null default now()
);

create table reconciliation_discrepancies (
    id               bigserial primary key,
    run_id           text not null,
    type             text not null,
    provider_ref     text not null,
    payment_id       text not null,
    detail           text not null,
    auto_resolvable  boolean not null,
    resolved         boolean not null default false,
    created_at       timestamptz not null default now()
);

-- A mandate nonce may only ever authorise one request, independent of the idempotency key.
create table mandate_nonces (
    mandate_id  text not null references mandates (mandate_id),
    nonce       text not null,
    payment_id  text not null,
    created_at  timestamptz not null default now(),
    primary key (mandate_id, nonce)
);
