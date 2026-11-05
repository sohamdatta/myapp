import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  formatDays, formatMinutes, formatRange, leaveBody, newLeave, numeric, shiftDay, useAction, useLoad, useNow, wholeNumber,
} from './hooks.js'

describe('useLoad', () => {
  it('starts loading, then holds the data', async () => {
    const { result } = renderHook(() => useLoad(() => Promise.resolve({ n: 1 })))
    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toEqual({ n: 1 })
    expect(result.current.error).toBe('')
  })

  it('holds the error message when the load fails', async () => {
    const { result } = renderHook(() => useLoad(() => Promise.reject(new Error('No access'))))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe('No access')
    expect(result.current.data).toBeNull()
  })

  it('loads again when reload() is called', async () => {
    const loader = vi.fn().mockResolvedValueOnce('first').mockResolvedValueOnce('second')
    const { result } = renderHook(() => useLoad(loader))
    await waitFor(() => expect(result.current.data).toBe('first'))
    act(() => result.current.reload())
    await waitFor(() => expect(result.current.data).toBe('second'))
    expect(loader).toHaveBeenCalledTimes(2)
  })

  it('ignores a slow earlier answer that arrives after a newer load', async () => {
    const answers = []
    const loader = () => new Promise((resolve) => answers.push(resolve))
    const { result } = renderHook(() => useLoad(loader))
    await waitFor(() => expect(answers).toHaveLength(1))
    act(() => result.current.reload())
    await waitFor(() => expect(answers).toHaveLength(2))
    await act(async () => answers[1]('newer'))
    await act(async () => answers[0]('older, but late'))
    expect(result.current.data).toBe('newer')
  })
})

describe('useAction', () => {
  it('is busy while the action runs and returns its result', async () => {
    let finish
    const { result } = renderHook(() => useAction())
    let outcome
    act(() => {
      outcome = result.current.run(() => new Promise((resolve) => { finish = resolve }))
    })
    expect(result.current.busy).toBe(true)
    await act(async () => {
      finish('done')
      await outcome
    })
    expect(result.current.busy).toBe(false)
    await expect(outcome).resolves.toBe('done')
  })

  it('keeps the error message and returns undefined when the action fails', async () => {
    const { result } = renderHook(() => useAction())
    let outcome
    await act(async () => {
      outcome = await result.current.run(() => Promise.reject(new Error('Too late')))
    })
    expect(outcome).toBeUndefined()
    expect(result.current.error).toBe('Too late')
    expect(result.current.busy).toBe(false)
    act(() => result.current.clearError())
    expect(result.current.error).toBe('')
  })
})

describe('useNow', () => {
  it('moves on every half minute and stops when unmounted', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-06T10:00:00Z'))
    const { result, unmount } = renderHook(() => useNow())
    const first = result.current
    act(() => vi.advanceTimersByTime(30000))
    expect(result.current - first).toBe(30000)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
    vi.useRealTimers()
  })
})

describe('number and date helpers', () => {
  it('reads Indian digit grouping and rejects text', () => {
    expect(numeric('1,00,000')).toBe(100000)
    expect(numeric('')).toBeNull()
    expect(() => numeric('abc', 'The amount')).toThrow('The amount must be a number')
  })

  it('wholeNumber refuses a fraction', () => {
    expect(wholeNumber('12')).toBe(12)
    expect(() => wholeNumber('1.5', 'Days')).toThrow('Days must be a whole number')
  })

  it('shifts a date across month and year ends', () => {
    expect(shiftDay('2026-12-31', 1)).toBe('2027-01-01')
    expect(shiftDay('2026-03-01', -1)).toBe('2026-02-28')
  })

  it('formats minutes, days and ranges', () => {
    expect(formatMinutes(0)).toBe('—')
    expect(formatMinutes(125)).toBe('2 h 05 min')
    expect(formatDays(1)).toBe('1 day')
    expect(formatDays(2.5)).toBe('2.5 days')
    expect(formatDays(null)).toBe('—')
    expect(formatRange('2026-11-03', '2026-11-03', 'first')).toMatch(/, first half$/)
  })

  it('makes a leave request body from the form', () => {
    const form = { ...newLeave(), leaveTypeId: 'x', reason: 'Wedding' }
    const body = leaveBody(form)
    expect(body.leaveTypeId).toBe('x')
    expect(body.reason).toBe('Wedding')
  })
})
