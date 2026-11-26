import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { linkify } from '@/lib/announcements/linkify';

/**
 * Message text is plain text: rendered only through `LinkedText` (React text + `linkify`: http and https only), never as markup,
 * never opened by anything but the in-app browser, never logged, never put in a URL or in storage.
 */
const SRC = join(__dirname, '..', '..');

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\.ts$/.test(name) ? [path] : [];
  });
}

/** The code without its comments (the comments explain what is NOT done). */
const codeOf = (file: string) => readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

const messagingFiles = [
  ...sources(join(SRC, 'lib', 'messaging')),
  ...sources(join(SRC, 'components', 'messaging')),
  ...sources(join(SRC, 'app', 'messages')),
];

describe('the messaging screens render plain text only', () => {
  it('finds the files', () => {
    expect(messagingFiles.length).toBeGreaterThan(20);
  });

  it('use no HTML, markdown or web view, and open links only through the in-app browser helper', () => {
    for (const file of messagingFiles) {
      const code = codeOf(file);
      expect(code, file).not.toMatch(/dangerouslySetInnerHTML|react-native-webview|WebView|markdown|Linking\.openURL/i);
    }
  });

  it('never log, store or put in a URL the words of a message', () => {
    for (const file of messagingFiles) {
      const code = codeOf(file);
      expect(code, file).not.toMatch(/console\.(log|info|warn|error|debug)\(/);
      expect(code, file).not.toMatch(/AsyncStorage|expo-sqlite|\bkv\.|SecureStore|localStorage|Clipboard/);
    }
  });

  it('show the text of a message through LinkedText', () => {
    const bubble = readFileSync(join(SRC, 'components', 'messaging', 'message-bubble.tsx'), 'utf8');
    expect(bubble).toContain('<LinkedText');
    // Both the stored message and the unsent one print their words through it.
    expect(bubble.match(/<LinkedText/g)?.length).toBeGreaterThanOrEqual(2);
  });

  it('keeps messages out of route params (only ids travel in a path)', () => {
    for (const file of messagingFiles) {
      const code = readFileSync(file, 'utf8');
      for (const match of code.matchAll(/router\.(push|replace|navigate)\(([^)]*)\)/g)) {
        expect(match[2], file).not.toMatch(/body|text|message\.body|draft/i);
      }
    }
  });
});

describe('links in messages (the announcements rules)', () => {
  it('only http and https become links', () => {
    const kinds = (text: string) => linkify(text).map((segment) => segment.type);
    expect(kinds('see https://school.example/a now')).toEqual(['text', 'link', 'text']);
    expect(kinds('javascript://x tel://1 mailto://a intent://x file:///etc/passwd')).toEqual(['text']);
    expect(kinds('https://trusted.example@evil.example/')).toEqual(['text']);
  });
});
