import { PRODUCT } from './brand.js'

const LIGHT = '/brand/nexvance-logo-light-bg.svg'
const DARK = '/brand/nexvance-logo-dark-bg.svg'

/**
 * The official logo (public/brand): hexagon NV mark, NEXVANCE and SOLUTIONS. `onDark` forces the version
 * for dark backgrounds; otherwise it follows the colour scheme. Keep it at least 36 high-ish (size 26+) so SOLUTIONS stays legible.
 */
export default function NexvanceLogo({ size = 36, onDark = false }) {
  const height = Math.round(size * 1.4)
  const width = Math.round((height * 996) / 269)
  const img = <img className="nexvance-logo" src={onDark ? DARK : LIGHT} alt={PRODUCT.fullName} height={height} width={width} />
  if (onDark) return img
  return (
    <picture>
      <source srcSet={DARK} media="(prefers-color-scheme: dark)" />
      {img}
    </picture>
  )
}
