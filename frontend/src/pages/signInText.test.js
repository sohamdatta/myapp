import { describe, expect, it } from 'vitest'
import { oidcMessage } from './signInText.js'

describe('oidcMessage', () => {
  it('words the codes the server sends back', () => {
    expect(oidcMessage('oidc.not_linked')).toMatch(/connect it under Security/)
    expect(oidcMessage('oidc.not_allowed')).toMatch(/has not turned on/)
    expect(oidcMessage('oidc.cancelled')).toMatch(/cancelled/)
  })
  it('says one thing for anything else, never the raw code', () => {
    expect(oidcMessage('oidc.invalid')).toBe(oidcMessage('something.else'))
    expect(oidcMessage(undefined)).not.toMatch(/oidc\./)
  })
})
