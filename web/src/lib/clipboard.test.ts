import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyText } from './clipboard';

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand');

function setClipboard(value: Pick<Clipboard, 'writeText'> | undefined) {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value,
  });
}

function setExecCommand(value: (command: string) => boolean) {
  Object.defineProperty(document, 'execCommand', {
    configurable: true,
    value,
  });
}

afterEach(() => {
  if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard);
  else Reflect.deleteProperty(navigator, 'clipboard');

  if (originalExecCommand) Object.defineProperty(document, 'execCommand', originalExecCommand);
  else Reflect.deleteProperty(document, 'execCommand');

  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('copyText', () => {
  it('uses the Clipboard API when it is available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    const execCommand = vi.fn(() => true);
    setClipboard({ writeText });
    setExecCommand(execCommand);

    await copyText('Broker log');

    expect(writeText).toHaveBeenCalledWith('Broker log');
    expect(execCommand).not.toHaveBeenCalled();
  });

  it('copies through a temporary textarea when Clipboard API is unavailable', async () => {
    let copiedValue = '';
    const trigger = document.createElement('button');
    document.body.appendChild(trigger);
    trigger.focus();
    setClipboard(undefined);
    setExecCommand(vi.fn(() => {
      copiedValue = document.querySelector('textarea')?.value || '';
      return true;
    }));

    await copyText('AD1000000001');

    expect(copiedValue).toBe('AD1000000001');
    expect(document.querySelector('textarea')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('falls back when the Clipboard API rejects the write', async () => {
    const writeText = vi.fn().mockRejectedValue(new DOMException('Not allowed', 'NotAllowedError'));
    const execCommand = vi.fn(() => true);
    setClipboard({ writeText });
    setExecCommand(execCommand);

    await expect(copyText('fallback text')).resolves.toBeUndefined();
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('reports a clear error when neither copy path succeeds', async () => {
    setClipboard(undefined);
    setExecCommand(vi.fn(() => false));

    await expect(copyText('cannot copy')).rejects.toThrow('复制失败：浏览器未授予剪贴板权限，请手动选择后复制');
    expect(document.querySelector('textarea')).not.toBeInTheDocument();
  });
});
