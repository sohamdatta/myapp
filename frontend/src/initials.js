/** Two letters for a person: from the name when there is one, otherwise from the email's local part. */
export function initials(name, email) {
  const source = (name || '').trim()
  if (source) {
    const parts = source.split(/\s+/)
    return ((parts[0][0] || '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase()
  }
  const local = (email || '?').split('@')[0].split(/[._-]+/).filter(Boolean)
  return ((local[0]?.[0] || '?') + (local[1]?.[0] || '')).toUpperCase()
}
