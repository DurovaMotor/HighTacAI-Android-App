const COPY_ERROR_MESSAGE = '复制失败：浏览器未授予剪贴板权限，请手动选择后复制';

function copyWithTextArea(text: string) {
  if (!document.body || typeof document.execCommand !== 'function') return false;

  const activeElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const selection = window.getSelection();
  const ranges = selection
    ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange())
    : [];
  const textArea = document.createElement('textarea');

  textArea.value = text;
  textArea.readOnly = true;
  textArea.tabIndex = -1;
  textArea.setAttribute('aria-hidden', 'true');
  Object.assign(textArea.style, {
    position: 'fixed',
    top: '0',
    left: '0',
    width: '1px',
    height: '1px',
    padding: '0',
    border: '0',
    opacity: '0',
  });

  document.body.appendChild(textArea);

  try {
    textArea.focus({ preventScroll: true });
    textArea.select();
    textArea.setSelectionRange(0, text.length);
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    textArea.remove();
    activeElement?.focus({ preventScroll: true });
    if (selection && ranges.length) {
      selection.removeAllRanges();
      ranges.forEach((range) => selection.addRange(range));
    }
  }
}

export async function copyText(text: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return;
    }
  } catch {
    // HTTP origins and denied permissions can reject the modern API; use the legacy path.
  }

  if (!copyWithTextArea(text)) throw new Error(COPY_ERROR_MESSAGE);
}
