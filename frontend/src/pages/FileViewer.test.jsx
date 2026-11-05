import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import FileViewer from './FileViewer.jsx'

const pdf = { name: 'Offer.pdf', type: 'application/pdf', url: 'blob:pdf' }
const photo = { name: 'Photo.png', type: 'image/png', url: 'blob:png' }

describe('FileViewer', () => {
  it('is a labelled dialog and puts keyboard focus on Close', () => {
    render(<FileViewer file={pdf} onClose={() => {}} />)
    expect(screen.getByRole('dialog', { name: 'Offer.pdf' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus()
  })

  it('gives focus back to where it was when it closes', () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const { unmount } = render(<FileViewer file={pdf} onClose={() => {}} />)
    expect(opener).not.toHaveFocus()
    unmount()
    expect(opener).toHaveFocus()
    opener.remove()
  })

  it('closes with Escape and with the Close button', async () => {
    const onClose = vi.fn()
    render(<FileViewer file={pdf} onClose={onClose} />)
    await userEvent.keyboard('{Escape}')
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('shows a PDF in a frame and a picture as an image, both with a download link', () => {
    const { rerender } = render(<FileViewer file={pdf} onClose={() => {}} />)
    expect(screen.getByTitle('Offer.pdf')).toHaveAttribute('src', 'blob:pdf')
    expect(screen.getByRole('link', { name: 'Download' })).toHaveAttribute('download', 'Offer.pdf')
    rerender(<FileViewer file={photo} onClose={() => {}} />)
    expect(screen.getByRole('img', { name: 'Photo.png' })).toHaveAttribute('src', 'blob:png')
  })

  it('frees the file from memory when it goes away', () => {
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const { unmount } = render(<FileViewer file={pdf} onClose={() => {}} />)
    unmount()
    expect(revoke).toHaveBeenCalledWith('blob:pdf')
  })
})
