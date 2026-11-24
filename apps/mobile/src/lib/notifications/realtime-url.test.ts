import { describe, expect, it } from 'vitest';

import { realtimeUrl } from './realtime-url';

describe('realtimeUrl', () => {
  it('maps http to ws and https to wss', () => {
    expect(realtimeUrl('http://localhost:8180')).toBe('ws://localhost:8180/ws');
    expect(realtimeUrl('https://api.school.example')).toBe('wss://api.school.example/ws');
  });
  it('drops path, query and trailing slashes', () => {
    expect(realtimeUrl('https://api.school.example/')).toBe('wss://api.school.example/ws');
    expect(realtimeUrl('https://api.school.example/v1/?x=1')).toBe('wss://api.school.example/ws');
    expect(realtimeUrl(' http://192.168.1.20:8180/// ')).toBe('ws://192.168.1.20:8180/ws');
  });
});
