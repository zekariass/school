export type PermissionState = 'granted' | 'denied' | 'undetermined';

export interface RegistrationInput {
  permission: PermissionState;
  /** EAS project id of the app; absent until EAS is linked. */
  projectId: string | undefined | null;
  platform: string;
}

export type RegistrationDecision =
  | { action: 'register'; platform: 'IOS' | 'ANDROID' }
  | { action: 'skip'; reason: 'permission' | 'no-project' | 'platform' };

/** Whether (and how) to register this device for push right now. */
export function decideRegistration({ permission, projectId, platform }: RegistrationInput): RegistrationDecision {
  if (permission !== 'granted') return { action: 'skip', reason: 'permission' };
  if (platform !== 'ios' && platform !== 'android') return { action: 'skip', reason: 'platform' };
  if (!projectId) return { action: 'skip', reason: 'no-project' };
  return { action: 'register', platform: platform === 'ios' ? 'IOS' : 'ANDROID' };
}

/**
 * The in-app explanation card comes before the OS prompt: shown to a signed-in user who has not
 * been asked yet and has not dismissed it. Never at launch, never in a developer preview.
 */
export function shouldShowPushCard(input: {
  signedIn: boolean;
  preview: boolean;
  permission: PermissionState | null;
  dismissed: boolean;
}): boolean {
  return input.signedIn && !input.preview && !input.dismissed && input.permission === 'undetermined';
}

/** The Expo push token format the API accepts. */
export function isExpoPushToken(token: string): boolean {
  return /^Expo(nent)?PushToken\[[A-Za-z0-9_-]{8,200}\]$/.test(token);
}
