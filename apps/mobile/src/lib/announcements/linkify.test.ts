import { describe, expect, it } from 'vitest';

import { linkify, safeWebUrl } from './linkify';

const links = (text: string) => linkify(text).flatMap((segment) => (segment.type === 'link' ? [segment.url] : []));
const joined = (text: string) => linkify(text).map((segment) => segment.text).join('');

describe('linkify', () => {
  it('links http and https addresses', () => {
    expect(links('see https://school.example/news?id=1#top now')).toEqual(['https://school.example/news?id=1#top']);
    expect(links('http://school.example')).toEqual(['http://school.example']);
  });
  it('never links other schemes: they stay plain text', () => {
    for (const text of ['javascript://x.example/%0Aalert(1)', 'intent://scan/#Intent;scheme=zxing;end', 'file:///etc/passwd', 'tel://123', 'mailto://a@b.c', 'school://invite/abc', 'data://text/html,x', 'ftp://files.example']) {
      expect(links(text)).toEqual([]);
    }
    expect(links('javascript:alert(1)')).toEqual([]);
  });
  it('keeps every character: the pieces joined are the input', () => {
    for (const text of ['plain', 'a https://x.example/a, b', '<script>alert(1)</script> https://x.example', '[x](https://x.example)', 'ሰላም https://x.example/a end.']) {
      expect(joined(text)).toBe(text);
    }
  });
  it('leaves markup as text', () => {
    expect(linkify('<b>bold</b> <a href="javascript:x">x</a>')).toEqual([{ type: 'text', text: '<b>bold</b> <a href="javascript:x">x</a>' }]);
  });
  it('does not take sentence punctuation into the address', () => {
    expect(links('Visit https://school.example/page.')).toEqual(['https://school.example/page']);
    expect(links('(see https://school.example/a)')).toEqual(['https://school.example/a']);
    expect(links('https://en.example/wiki/Foo_(bar)')).toEqual(['https://en.example/wiki/Foo_(bar)']);
  });
  it('rejects addresses that show one host and open another', () => {
    expect(links('https://trusted.example@evil.example/login')).toEqual([]);
    expect(links('https://trusted.example\\@evil.example')).toEqual([]);
  });
  it('rejects hosts without a dot and invisible characters', () => {
    expect(links('https://intranet/')).toEqual([]);
    expect(links('https://sch​ool.example/')).toEqual([]);
    expect(links('https://school.example/‮evil')).toEqual([]);
  });
  it('is bounded: only the first links are links, a huge address is text', () => {
    const many = Array.from({ length: 50 }, (_, i) => `https://a${i}.example`).join(' ');
    expect(links(many).length).toBe(20);
    expect(links(`https://x.example/${'a'.repeat(2500)}`)).toEqual([]);
  });
});

describe('safeWebUrl', () => {
  it('accepts ports, paths and IPv4 hosts', () => {
    expect(safeWebUrl('https://school.example:8443/a')).toBe('https://school.example:8443/a');
    expect(safeWebUrl('http://10.0.0.5/status')).toBe('http://10.0.0.5/status');
  });
  it('refuses everything else', () => {
    for (const text of ['', 'ftp://a.example', 'https://', 'https:///x', '//a.example', 'https:/a.example', 'ws://a.example']) {
      expect(safeWebUrl(text)).toBeNull();
    }
  });
});
