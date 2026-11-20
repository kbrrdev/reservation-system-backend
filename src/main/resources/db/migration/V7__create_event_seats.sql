create table ticket_type_sections (
    id             uuid primary key,
    event_id       uuid not null references events (id) on delete cascade,
    ticket_type_id uuid not null references ticket_types (id) on delete cascade,
    section_id     uuid not null references sections (id),
    unique (event_id, section_id)
);
create table event_seats (
    id             uuid primary key,
    event_id       uuid        not null references events (id) on delete cascade,
    seat_id        uuid        not null references seats (id),
    ticket_type_id uuid        not null references ticket_types (id),
    status         varchar(10) not null check (status in ('AVAILABLE', 'HELD', 'SOLD')),
    booking_id     uuid        references bookings (id),
    unique (event_id, seat_id),
    check ((status = 'AVAILABLE') = (booking_id is null))
);
create index event_seats_booking on event_seats (booking_id) where booking_id is not null;
