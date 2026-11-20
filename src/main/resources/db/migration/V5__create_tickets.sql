create table tickets (
    id             uuid primary key,
    booking_id     uuid        not null references bookings (id),
    ticket_type_id uuid        not null references ticket_types (id),
    code           varchar(32) not null unique,
    status         varchar(10) not null check (status in ('VALID', 'VOID', 'USED')),
    issued_at      timestamptz not null
);
create index tickets_booking on tickets (booking_id);
