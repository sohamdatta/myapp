import { PRODUCT } from './brand.js'

/** The mark alone: an N whose right stroke rises into a forward arrow. Coloured by --accent. */
export function NexvanceMark({ size = 30 }) {
  return (
    <svg className="nexvance-mark" width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="8" fill="var(--accent)" />
      <path d="M9 23V9.5l10 11.5V11" fill="none" stroke="var(--on-accent)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M15.5 13.5 19 9.5l3.5 4" fill="none" stroke="var(--on-accent)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/** The wordmark as on nexvance.in: NEXVANCE, with Solutions beneath, beside the mark. */
export default function NexvanceLogo({ size = 30, tagline = false }) {
  return (
    <span className="nexvance-logo" role="img" aria-label={PRODUCT.fullName}>
      <NexvanceMark size={size} />
      <span className="nexvance-words" aria-hidden="true">
        <span className="nexvance-name">NEXVANCE</span>
        <span className="nexvance-sub">{tagline ? PRODUCT.tagline : 'Solutions'}</span>
      </span>
    </span>
  )
}
