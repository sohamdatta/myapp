import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Badge, Empty, ErrorNote, Tabs } from './ui.jsx'

const tabs = [{ id: 'a', label: 'Alpha' }, { id: 'b', label: 'Beta' }, { id: 'c', label: 'Gamma' }]

function Harness({ onChange = () => {} }) {
  const [current, setCurrent] = useState('a')
  return <Tabs tabs={tabs} current={current} onChange={(id) => { setCurrent(id); onChange(id) }} />
}

describe('Tabs', () => {
  it('marks the current tab, and only that one is in the Tab order', () => {
    render(<Harness />)
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveAttribute('tabindex', '-1')
  })

  it('changes tab on click', async () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    await userEvent.click(screen.getByRole('tab', { name: 'Beta' }))
    expect(onChange).toHaveBeenCalledWith('b')
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveAttribute('aria-selected', 'true')
  })

  it('moves with the arrow keys, wrapping at the ends, and with Home and End', async () => {
    render(<Harness />)
    screen.getByRole('tab', { name: 'Alpha' }).focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveFocus()
    await userEvent.keyboard('{End}')
    expect(screen.getByRole('tab', { name: 'Gamma' })).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveFocus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(screen.getByRole('tab', { name: 'Gamma' })).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: 'Alpha' })).toHaveAttribute('aria-selected', 'true')
  })

  it('ignores other keys', async () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    screen.getByRole('tab', { name: 'Alpha' }).focus()
    await userEvent.keyboard('x')
    expect(onChange).not.toHaveBeenCalled()
  })
})

describe('small components', () => {
  it('ErrorNote shows nothing without a message and announces one when there is', () => {
    const { rerender, container } = render(<ErrorNote>{''}</ErrorNote>)
    expect(container).toBeEmptyDOMElement()
    rerender(<ErrorNote>Could not save</ErrorNote>)
    expect(screen.getByRole('alert')).toHaveTextContent('Could not save')
  })

  it('Badge and Empty render their text', () => {
    render(<><Badge tone="good">Active</Badge><Empty>Nothing here</Empty></>)
    expect(screen.getByText('Active')).toHaveClass('badge-good')
    expect(screen.getByText('Nothing here')).toHaveClass('empty')
  })
})
