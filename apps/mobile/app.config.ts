import type { ConfigContext, ExpoConfig } from 'expo/config';

/**
 * Extends `app.json`. Invitation links (`https://{host}/{language}/invite/{token}`) open the installed
 * app when `SCHOOL_UNIVERSAL_LINK_HOST` names the web host that serves them (the host of
 * `SCHOOL_GUARDIANS_INVITE_BASE_URL` on the backend). It is opt-in because associated domains need a paid
 * Apple developer team and the host must serve `apple-app-site-association` and `assetlinks.json`
 * (see the README). Without it, the app still opens `school://invite/{token}` and the web page works for
 * everyone else.
 */
export default ({ config }: ConfigContext): ExpoConfig => {
  const host = process.env.SCHOOL_UNIVERSAL_LINK_HOST?.trim();
  const base = config as ExpoConfig;
  if (!host) return base;
  return {
    ...base,
    ios: { ...base.ios, associatedDomains: [`applinks:${host}`] },
    android: {
      ...base.android,
      intentFilters: [
        {
          action: 'VIEW',
          autoVerify: true,
          data: ['en', 'am'].map((language) => ({ scheme: 'https', host, pathPrefix: `/${language}/invite` })),
          category: ['BROWSABLE', 'DEFAULT'],
        },
      ],
    },
  };
};
