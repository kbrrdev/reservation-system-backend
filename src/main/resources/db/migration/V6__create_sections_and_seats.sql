create table sections (
    id       uuid primary key,
    venue_id uuid         not null references venues (id),
    name     varchar(100) not null,
    unique (venue_id, name)
);
create table seats (
    id          uuid primary key,
    section_id  uuid        not null references sections (id),
    row_label   varchar(10) not null,
    row_index   int         not null check (row_index >= 0),
    seat_number int         not null check (seat_number >= 1),
    unique (section_id, row_label, seat_number)
);
