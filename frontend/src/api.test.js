import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { downloadFile, fetchFile, forgetSignInPage, loadSignInPage, login, postFile, saveBlob, scopeToPage } from './api.js'

describe('saveBlob', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:file')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('does not free the address straight after the click, which can cancel the download', () => {
    saveBlob(new Blob(['a,b']), 'register.csv')
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledTimes(1)
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    vi.advanceTimersByTime(10000)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:file')
  })
})

describe('file calls', () => {
  afterEach(() => vi.restoreAllMocks())

  it('fetchFile reports a lost connection in the same words as the other calls', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(fetchFile('/org/x')).rejects.toMatchObject({ message: expect.stringContaining('Cannot reach the server'), code: 'network' })
  })

  it('postFile reports a lost connection too', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(postFile('/org/x', {})).rejects.toMatchObject({ code: 'network' })
  })

  it('fetchFile turns an error answer into the server message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ message: 'Calculate the run first', code: 'payroll.state' }) }))
    await expect(fetchFile('/org/payroll/runs/1/outputs/register')).rejects.toMatchObject({ message: 'Calculate the run first', status: 409 })
  })

  it('fetchFile still gives a message when the error has no JSON body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => { throw new Error('not json') } }))
    await expect(fetchFile('/org/x')).rejects.toMatchObject({ message: 'The file could not be opened.', status: 500 })
  })

  it('downloadFile saves what fetchFile returns', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['x']) }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:file')
    await expect(downloadFile('/org/payroll/runs/1/outputs/register', 'register.csv')).resolves.toBe(true)
    expect(click).toHaveBeenCalledTimes(1)
  })
})

describe('an Organization’s own sign-in address', () => {
  const ACME = { id: 'acme-id', name: 'Acme', slug: 'acme', logoVersion: 2 }
  const me = (extra = {}) => ({
    token: 't',
    kind: 'identity',
    organizations: [
      { tenantId: 'other-id', tenantName: 'Other' },
      { tenantId: 'acme-id', tenantName: 'Acme' },
    ],
    console: 'super_admin',
    ...extra,
  })
  const answer = (data) => ({ ok: true, status: 200, json: async () => data })

  beforeEach(() => forgetSignInPage())
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    localStorage.clear()
    forgetSignInPage()
  })

  it('asks which page this is only once per visit', async () => {
    const fetch = vi.fn().mockResolvedValue(answer({ organization: ACME, unknown: false }))
    vi.stubGlobal('fetch', fetch)
    await Promise.all([loadSignInPage(), loadSignInPage()])
    await loadSignInPage()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('goes straight into that Organization, whatever was used last on this browser', async () => {
    localStorage.setItem('hrms-last-destination', 'other-id')
    const calls = []
    vi.stubGlobal('fetch', vi.fn(async (url, init) => {
      calls.push([url, init?.body])
      if (url === '/api/public/sign-in-page') return answer({ organization: ACME, unknown: false })
      if (url === '/api/auth/login') return answer(me())
      if (url === '/api/auth/enter') return answer(me({ kind: 'tenant', organization: { id: 'acme-id', name: 'Acme' } }))
      throw new Error(`unexpected ${url}`)
    }))
    // Not asked beforehand: signing in waits for the answer itself.
    const signedIn = await login('a@acme.test', 'pw')
    expect(signedIn.organization.id).toBe('acme-id')
    expect(calls.find(([url]) => url === '/api/auth/enter')[1]).toBe(JSON.stringify({ organizationId: 'acme-id' }))
  })

  it('offers only that Organization to switch to, and never the console', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer({ organization: ACME, unknown: false })))
    await loadSignInPage()
    const scoped = scopeToPage(me())
    expect(scoped.organizations.map((o) => o.tenantId)).toEqual(['acme-id'])
    expect(scoped.console).toBeNull()
  })

  it('does not keep a failed answer: the next call asks again', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValue(answer({ organization: ACME, unknown: false }))
    vi.stubGlobal('fetch', fetch)
    expect((await loadSignInPage()).organization).toBeNull()
    expect((await loadSignInPage()).organization.id).toBe('acme-id')
    expect(scopeToPage(me()).organizations.map((o) => o.tenantId)).toEqual(['acme-id'])
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('takes the shared address’s own answer as it is, and passes an unknown address on', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(answer({ organization: null, unknown: false, sharedAddress: 'https://hr.example.com' })))
    const shared = await loadSignInPage()
    expect(shared).toEqual({ organization: null, unknown: false, sharedAddress: 'https://hr.example.com' })
    expect(scopeToPage(me())).toEqual(me())

    forgetSignInPage()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(answer({ organization: null, unknown: true, sharedAddress: 'https://hr.example.com' })))
    const unknown = await loadSignInPage()
    expect(unknown.unknown).toBe(true)
    expect(unknown.sharedAddress).toBe('https://hr.example.com')
  })

  describe('where a new sign-in goes', () => {
    const route = (page, handlers = {}) => {
      const calls = []
      vi.stubGlobal('fetch', vi.fn(async (url, init) => {
        calls.push([url, init?.body])
        if (url === '/api/public/sign-in-page') return answer(page)
        if (url === '/api/auth/login') return answer(me(handlers.login))
        if (url === '/api/auth/enter') {
          const body = JSON.parse(init.body)
          if (handlers.enterFails) return { ok: false, status: 403, json: async () => handlers.enterFails }
          return answer(body.console ? me({ kind: 'console' }) : me({ kind: 'tenant', organization: { id: body.organizationId } }))
        }
        throw new Error(`unexpected ${url}`)
      }))
      return calls
    }
    const entered = (calls) => calls.filter(([url]) => url === '/api/auth/enter').map(([, body]) => JSON.parse(body))

    it('leaves a login that is not a member of this Organization on the screen that lists where it can go', async () => {
      const calls = route({ organization: { ...ACME, id: 'elsewhere-id' }, unknown: false })
      const result = await login('a@acme.test', 'pw')
      expect(result.kind).toBe('identity')
      expect(entered(calls)).toEqual([])
    })

    it('on the shared address goes to the console when that was last used and the login has it', async () => {
      localStorage.setItem('hrms-last-destination', 'console')
      const calls = route({ organization: null, unknown: false })
      await login('a@acme.test', 'pw')
      expect(entered(calls)).toEqual([{ console: true }])
    })

    it('on the shared address goes to the first Organization when the last one is no longer available', async () => {
      localStorage.setItem('hrms-last-destination', 'gone-id')
      const calls = route({ organization: null, unknown: false })
      await login('a@acme.test', 'pw')
      expect(entered(calls)).toEqual([{ organizationId: 'other-id' }])
    })

    it('says why an entry was refused when the Organization needs two-step sign-in', async () => {
      route({ organization: ACME, unknown: false }, { enterFails: { code: 'mfa.required', message: 'Turn on two-step sign-in first' } })
      const result = await login('a@acme.test', 'pw')
      expect(result.entryRefused).toEqual({ code: 'mfa.required', message: 'Turn on two-step sign-in first' })
    })
  })

  it('changes nothing on the shared address, or when the page cannot be told', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const page = await loadSignInPage()
    expect(page.organization).toBeNull()
    expect(scopeToPage(me())).toEqual(me())
  })
})
