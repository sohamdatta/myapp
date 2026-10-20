import { createContext, useContext } from 'react'

export const ToastContext = createContext(() => {})
export const ConfirmContext = createContext(async () => true)

/** toast('Leave approved for Priya') — a short note that something worked. See feedback.jsx. */
export const useToast = () => useContext(ToastContext)

/**
 * const confirm = useConfirm()
 * if (!(await confirm({ title: 'Reject this leave?', body: '…', confirmLabel: 'Reject', danger: true }))) return
 */
export const useConfirm = () => useContext(ConfirmContext)
