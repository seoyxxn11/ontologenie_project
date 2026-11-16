// Capture input before React opens a panel; initial page entry is separate from keyboard use.
export function trackUiMotionInput() {
  const root = document.documentElement
  const previousInput = root.getAttribute('data-ui-input')
  root.dataset.uiInput = 'initial'

  function pointerInput() {
    if (root.dataset.uiInput !== 'pointer') root.dataset.uiInput = 'pointer'
  }
  function keyboardInput() {
    if (root.dataset.uiInput !== 'keyboard') root.dataset.uiInput = 'keyboard'
  }

  document.addEventListener('pointerdown', pointerInput, true)
  document.addEventListener('keydown', keyboardInput, true)
  return () => {
    document.removeEventListener('pointerdown', pointerInput, true)
    document.removeEventListener('keydown', keyboardInput, true)
    if (previousInput === null) delete root.dataset.uiInput
    else root.setAttribute('data-ui-input', previousInput)
  }
}
