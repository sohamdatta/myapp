import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { downloadFile, fetchFile, postFile, saveBlob } from './api.js'

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
