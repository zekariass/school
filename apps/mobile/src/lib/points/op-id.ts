import { randomUUID } from 'expo-crypto';

/** One id per user action (the API's `clientOpId`); a retry of the same action reuses it. */
export const newOpId = (): string => randomUUID();
