-- D42: a country cannot be switched to ENABLED until it has its own published TERMS and PRIVACY text.
-- Schools and sign-ins in a country with no terms or privacy notice would have nothing lawful to show an adult, and code
-- never borrows another country's legal text. The photo and account-participation consents are not required here:
-- without MEDIA_PUBLICATION text of its own a country's schools simply cannot show children's photos (the consent gate
-- withholds them).
--
-- The trigger fires when the status BECOMES 'ENABLED' (an update). A row that is inserted already enabled is a seed;
-- the application refuses to start when an enabled country lacks the two documents (consent.CountryPoliciesCheck).

create function countries_require_policies() returns trigger language plpgsql as $$
declare
	missing text;
begin
	if new.status = 'ENABLED' and old.status is distinct from 'ENABLED' then
		select string_agg(t.policy_type, ', ' order by t.policy_type) into missing
		from (values ('PRIVACY'), ('TERMS')) as t (policy_type)
		where not exists (
			select 1
			from policy_documents d
			join policy_versions v on v.policy_document_id = d.id
			where d.country_code = new.code and d.school_id is null and d.policy_type = t.policy_type
			  and v.published_at is not null and v.effective_from <= now());
		if missing is not null then
			raise exception 'country.policies_missing: % cannot be enabled, it has no published %', new.code, missing
				using errcode = '23514';
		end if;
	end if;
	return new;
end
$$;

create trigger tr_countries_require_policies before update of status on countries
	for each row execute function countries_require_policies();
