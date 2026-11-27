import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { asCountryCode, needsCountryPicker, pickPhoneCountry } from '@school/validation/country';

import { countriesQuery } from './queries';

/**
 * The country a phone number is read in before sign-in (D42): the platform default from `GET /meta`, or the one the
 * person picks when more than one country is enabled. A number that starts with "+" needs no picker. Memory only.
 */
export function usePhoneCountry() {
  const meta = useQuery(countriesQuery);
  const [selected, setSelected] = useState<string | null>(null);
  const countries = useMemo(() => meta.data?.countries ?? [], [meta.data]);
  const country = pickPhoneCountry(countries, meta.data?.defaultCountry, selected);
  return {
    /** `/meta` has not answered yet. */
    loading: meta.isPending,
    countries,
    country,
    /** For `phoneSchema` and `parseDestinationInput`; undefined when no country is known (only "+" numbers parse). */
    regionCode: asCountryCode(country?.code),
    showPicker: needsCountryPicker(countries),
    select: setSelected,
  };
}
