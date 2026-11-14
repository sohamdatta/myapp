import { describe, expect, it } from 'vitest'
import { canDo, recentTaxYears, taxYearOf } from './payrollText.js'

describe('taxYearOf', () => {
  it('puts March in the year that began the April before', () => {
    expect(taxYearOf('2027-03-31')).toBe('2026-27')
  })
  it('starts a new year on 1 April', () => {
    expect(taxYearOf('2026-04-01')).toBe('2026-27')
  })
  it('rolls the century digits correctly', () => {
    expect(taxYearOf('2099-04-01')).toBe('2099-00')
  })
})

describe('recentTaxYears', () => {
  it('lists the current year first', () => {
    expect(recentTaxYears(3, '2026-11-14')).toEqual(['2026-27', '2025-26', '2024-25'])
  })
})

describe('canDo', () => {
  it('is true for tenant and assigned scope only', () => {
    expect(canDo({ scopes: { 'payroll.prepare': 'tenant' } }, 'payroll.prepare')).toBe(true)
    expect(canDo({ scopes: { 'payroll.prepare': 'assigned' } }, 'payroll.prepare')).toBe(true)
    expect(canDo({ scopes: { 'payroll.prepare': 'self' } }, 'payroll.prepare')).toBe(false)
    expect(canDo({ scopes: {} }, 'payroll.prepare')).toBe(false)
    expect(canDo(null, 'payroll.prepare')).toBe(false)
  })
})
