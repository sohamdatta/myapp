/** Which build of the screens this is. Set at build time by deploy/hrms.sh; 'dev' when run locally. */
export const APP = {
  version: import.meta.env.VITE_APP_VERSION || 'dev',
  commit: import.meta.env.VITE_APP_COMMIT || 'dev',
  built: import.meta.env.VITE_APP_BUILT || '',
}

/** What people see: the release number only. The commit is in the hover text and at /api/version. */
export const versionLabel = (v) => (v.version === 'dev' ? 'dev build' : `v${v.version}`)
export const buildLabel = (v) => (v.version === 'dev' ? 'dev build' : `v${v.version} (${v.commit})`)
