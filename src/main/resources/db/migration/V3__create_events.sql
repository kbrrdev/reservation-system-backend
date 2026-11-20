create table events (
    id                       uuid primary key,
    organizer_id             uuid         not null references users (id),
    venue_id                 uuid         not null references venues (id),
    currency                 char(3)      not null,
    title                    varchar(200) not null,
    description              varchar(5000),
    category                 varchar(20)  not null,
    starts_at                timestamptz  not null,
    ends_at                  timestamptz  not null,
    sale_starts_at           timestamptz  not null,
    seating_mode             varchar(10)  not null check (seating_mode in ('RESERVED', 'GA')),
    max_tickets_per_customer int          not null check (max_tickets_per_customer >= 1),
    status                   varchar(20)  not null check (status in ('DRAFT','PENDING_APPROVAL','REJECTED','APPROVED','ON_SALE','ENDED','CANCELLED')),
    created_at               timestamptz  not null,
    version                  bigint       not null default 0,
    check (sale_starts_at < starts_at and starts_at < ends_at)
);
create index events_on_sale_starts on events (starts_at) where status = 'ON_SALE';
create table ticket_types (
    id          uuid primary key,
    event_id    uuid         not null references events (id) on delete cascade,
    name        varchar(100) not null,
    price_minor bigint       not null check (price_minor >= 0),
    capacity    int          not null check (capacity >= 1),
    held        int          not null default 0 check (held >= 0),
    sold        int          not null default 0 check (sold >= 0),
    version     bigint       not null default 0,
    unique (event_id, name),
    check (held + sold <= capacity)   -- last line of defence against oversell
);
