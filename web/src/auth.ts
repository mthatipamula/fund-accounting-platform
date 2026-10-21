import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';

declare global {
  interface Window { __CONFIG__: { oidcAuthority: string; oidcClientId: string } }
}

export const users = new UserManager({
  authority: window.__CONFIG__.oidcAuthority,
  client_id: window.__CONFIG__.oidcClientId,
  redirect_uri: `${window.location.origin}/`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',          // authorization code + PKCE
  scope: 'openid profile email',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
});

export type Role = 'fa-viewer' | 'fa-accountant' | 'fa-admin';

/** Roles from the access token, used only to hide controls; every service enforces them itself. */
export function rolesOf(user: User | null): Set<Role> {
  if (!user) return new Set();
  try {
    const payload = JSON.parse(atob(user.access_token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return new Set((payload.realm_access?.roles ?? []).filter((r: string) => r.startsWith('fa-')));
  } catch { return new Set(); }
}

/** Finishes a sign-in redirect if this page load is one; otherwise returns the stored session. */
export async function initialUser(): Promise<User | null> {
  const params = new URLSearchParams(window.location.search);
  if (params.has('code') && params.has('state')) {
    const user = await users.signinRedirectCallback();
    window.history.replaceState({}, '', window.location.pathname + window.location.hash);
    return user;
  }
  const user = await users.getUser();
  return user && !user.expired ? user : null;
}
