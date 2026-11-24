-- Phase 0 · Database_Design §1 (Extensions).
-- citext: case-insensitive emails/usernames/slugs
-- btree_gist: exclusion constraints (tutor double-booking, §4.27)
-- pg_trgm: fuzzy name/title search
create extension if not exists citext;
create extension if not exists btree_gist;
create extension if not exists pg_trgm;
