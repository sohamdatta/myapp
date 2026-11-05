/** Which build of the screens this is. Set at build time by deploy/hrms.sh; 'dev' when run locally. */
export const APP = {
  version: import.meta.env.VITE_APP_VERSION || 'dev',
  commit: import.meta.env.VITE_APP_COMMIT || 'dev',
  built: import.meta.env.VITE_APP_BUILT || '',
}

export const versionLabel = (v) => (v.version === 'dev' ? 'dev build' : `v${v.version} · ${v.commit}`)
