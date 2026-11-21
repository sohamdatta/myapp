import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LoginForm } from './SignIn.jsx'
import OrgSettings from './OrgSettings.jsx'

/** Answers each API call from a table of paths; anything else is a 404. */
function serve(routes, calls = []) {
  vi.stubGlobal('fetch', vi.fn(async (url, init = {}) => {
    const path = url.replace(/^\/api/, '').split('?')[0]
    calls.push({ method: init.method || 'GET', path, body: init.body && JSON.parse(init.body) })
    const key = `${init.method || 'GET'} ${path}`
    const data = typeof routes[key] === 'function' ? routes[key](init) : routes[key]
    if (data && data.__error) return { ok: false, status: data.__error.status, json: async () => data.__error, headers: new Headers() }
    if (data === undefined) return { ok: false, status: 404, json: async () => ({ message: 'not found', code: 'not_found' }) }
    return { ok: true, status: 200, json: async () => data, headers: new Headers() }
  }))
  return calls
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the sign-in page on an Organization’s own address', () => {
  const acme = { id: 'acme-id', name: 'Acme Ltd', slug: 'acme', logoVersion: 3 }

  it('shows the Organization’s logo and name instead of the product’s promise', async () => {
    serve({ 'GET /auth/providers': { providers: [] }, 'GET /version': { version: 'test' } })
    render(<LoginForm onSignedIn={() => {}} organization={acme} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Sign in to Acme Ltd' })).toBeInTheDocument()
    const logos = screen.getAllByRole('img', { name: 'Acme Ltd logo' })
    expect(logos[0]).toHaveAttribute('src', '/api/branding/acme-id/logo?v=3')
    expect(screen.getByText('Powered by Nexvance')).toBeInTheDocument()
    expect(screen.queryByText('People operations, made simple for India.')).not.toBeInTheDocument()
    expect(screen.getByText(/Only people in Acme Ltd can sign in here/)).toBeInTheDocument()
  })

  it('shows the name when the Organization has no logo of its own', () => {
    serve({ 'GET /auth/providers': { providers: [] } })
    render(<LoginForm onSignedIn={() => {}} organization={{ ...acme, logoVersion: 0 }} />)
    expect(screen.queryByRole('img', { name: 'Acme Ltd logo' })).not.toBeInTheDocument()
    expect(screen.getAllByTestId('org-mark')[0]).toHaveTextContent('Acme Ltd')
  })

  it('is the usual page on the shared address', () => {
    serve({ 'GET /auth/providers': { providers: [] } })
    render(<LoginForm onSignedIn={() => {}} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Welcome back' })).toBeInTheDocument()
    expect(screen.queryByTestId('org-mark')).not.toBeInTheDocument()
  })
})

describe('the sign-in address in Organization settings', () => {
  const me = { organization: { id: 'acme-id', name: 'Acme Ltd' }, modules: [] }
  const base = {
    'GET /org/setup/branding': { logoVersion: 0, canChange: true },
    'GET /org/sign-in': {},
    'GET /auth/providers': { providers: [] },
  }
  const address = { slug: 'acme', address: 'https://acme.hr.example.com', canChange: true, enabled: true }

  it('is not offered where this server does not serve the addresses', async () => {
    const calls = serve({ ...base, 'GET /org/setup/sign-in-address': { ...address, enabled: false } })
    render(<OrgSettings me={me} />)
    await screen.findByTestId('branding')
    await screen.findByTestId('email-domain-settings')
    // The panel hides because the answer said so, not because it had not arrived yet.
    await waitFor(() => expect(calls.some((c) => c.path === '/org/setup/sign-in-address')).toBe(true))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByTestId('sign-in-address')).not.toBeInTheDocument()
  })

  it('warns that the old address stops working before the admin changes it, then saves the new one', async () => {
    const calls = serve({
      ...base,
      'GET /org/setup/sign-in-address': address,
      'PUT /org/setup/sign-in-address': { ...address, slug: 'acme-hr', address: 'https://acme-hr.hr.example.com' },
    })
    render(<OrgSettings me={me} />)
    const panel = await screen.findByTestId('sign-in-address')
    expect(within(panel).getByTestId('sign-in-address-link')).toHaveTextContent('https://acme.hr.example.com')
    const save = within(panel).getByRole('button', { name: 'Change the address' })
    expect(save).toBeDisabled()

    const input = within(panel).getByLabelText('Short name')
    await userEvent.clear(input)
    await userEvent.type(input, 'acme-hr')
    expect(panel).toHaveTextContent('The old address, https://acme.hr.example.com, stops working as soon as you save.')
    await userEvent.click(save)

    expect(await within(panel).findByText('Saved. Your sign-in address has changed.')).toBeInTheDocument()
    expect(within(panel).getByTestId('sign-in-address-link')).toHaveTextContent('https://acme-hr.hr.example.com')
    expect(calls.find((c) => c.method === 'PUT').body).toEqual({ slug: 'acme-hr' })
  })

  it.each([
    ['tenant.slug_taken', 'That short name is already taken'],
    ['tenant.slug_held', 'That short name was given up recently and is held for 90 days'],
    ['tenant.slug_reserved', 'That short name is reserved'],
  ])('tells the admin when the short name is refused (%s) and keeps the old address', async (code, message) => {
    serve({
      ...base,
      'GET /org/setup/sign-in-address': address,
      'PUT /org/setup/sign-in-address': { __error: { status: 409, code, message } },
    })
    render(<OrgSettings me={me} />)
    const panel = await screen.findByTestId('sign-in-address')
    const input = within(panel).getByLabelText('Short name')
    await userEvent.clear(input)
    await userEvent.type(input, 'acme-hr')
    await userEvent.click(within(panel).getByRole('button', { name: 'Change the address' }))

    expect(await within(panel).findByText(message)).toBeInTheDocument()
    expect(within(panel).queryByText('Saved. Your sign-in address has changed.')).not.toBeInTheDocument()
    expect(within(panel).getByTestId('sign-in-address-link')).toHaveTextContent('https://acme.hr.example.com')
    expect(within(panel).getByLabelText('Short name')).toHaveValue('acme-hr')
  })

  it('shows the address to share, without the form, to someone who cannot change it', async () => {
    serve({ ...base, 'GET /org/setup/sign-in-address': { ...address, canChange: false } })
    render(<OrgSettings me={me} />)
    const panel = await screen.findByTestId('sign-in-address')
    expect(within(panel).queryByLabelText('Short name')).not.toBeInTheDocument()
    expect(within(panel).getByTestId('sign-in-address-link')).toHaveTextContent('https://acme.hr.example.com')
  })
})
