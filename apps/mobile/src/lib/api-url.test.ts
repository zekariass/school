import { describe, expect, it } from 'vitest';

import { FALLBACK_API_URL, hostFromHostUri, resolveApiBaseUrl } from './api-url';

describe('resolveApiBaseUrl', () => {
  it('prefers EXPO_PUBLIC_API_URL and trims trailing slashes', () => {
    expect(
      resolveApiBaseUrl({ envUrl: ' https://api.example.com// ', hostUri: '10.0.0.5:8081', isDev: true }),
    ).toBe('https://api.example.com');
  });

  it('derives the backend from the Metro host in development', () => {
    expect(resolveApiBaseUrl({ hostUri: '192.168.1.20:8081', isDev: true })).toBe('http://192.168.1.20:8180');
    expect(resolveApiBaseUrl({ envUrl: '', hostUri: 'localhost:8081', isDev: true })).toBe(
      'http://localhost:8180',
    );
  });

  it('ignores the Metro host in release builds', () => {
    expect(resolveApiBaseUrl({ hostUri: '192.168.1.20:8081', isDev: false })).toBe(FALLBACK_API_URL);
  });

  it('falls back to localhost without any hint', () => {
    expect(resolveApiBaseUrl({ isDev: true })).toBe('http://localhost:8180');
    expect(resolveApiBaseUrl({ hostUri: null, isDev: true })).toBe('http://localhost:8180');
  });

  it('does not derive from tunnel hosts', () => {
    expect(resolveApiBaseUrl({ hostUri: 'abc-123.exp.direct:80', isDev: true })).toBe(FALLBACK_API_URL);
  });
});

describe('hostFromHostUri', () => {
  it('parses host:port, schemes, paths and IPv6', () => {
    expect(hostFromHostUri('10.0.2.2:8081')).toBe('10.0.2.2');
    expect(hostFromHostUri('http://my-mac.local:8081/index.bundle')).toBe('my-mac.local');
    expect(hostFromHostUri('[::1]:8081')).toBe('[::1]');
    expect(hostFromHostUri('myhost')).toBe('myhost');
  });

  it('returns undefined for empty input', () => {
    expect(hostFromHostUri('')).toBeUndefined();
    expect(hostFromHostUri(':8081')).toBeUndefined();
  });
});
