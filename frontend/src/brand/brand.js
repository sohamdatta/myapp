/**
 * The product's own brand, shown wherever an Organization has not set its own logo, and always on
 * the sign-in page. To change the artwork, replace NexvanceLogo.jsx and public/favicon.svg.
 */
export const PRODUCT = {
  name: 'Nexvance',
  fullName: 'Nexvance Solutions',
  tagline: "Advancing What's Next",
  site: 'https://nexvance.in',
}

/** Where an Organization's own logo is served from; the version changes with each new logo. */
export const logoUrl = (organization) =>
  organization && organization.logoVersion > 0 ? `/api/branding/${organization.id}/logo?v=${organization.logoVersion}` : null
