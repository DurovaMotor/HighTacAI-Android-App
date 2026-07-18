import { App as AntApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ImportJob } from '../api/types';
import { ImportProductsModal } from './ProductsPage';

const mocks = vi.hoisted(() => ({
  previewImport: vi.fn(),
  importJob: vi.fn(),
  commitImport: vi.fn(),
  template: vi.fn(),
  importErrors: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  api: {
    products: {
      previewImport: mocks.previewImport,
      importJob: mocks.importJob,
      commitImport: mocks.commitImport,
      template: mocks.template,
      importErrors: mocks.importErrors,
    },
  },
}));

function importJob(status: ImportJob['status']): ImportJob {
  return {
    id: '0d15e762-38f9-4b9e-ab72-e4c40482816d',
    filename: 'products.xlsx',
    status,
    total_rows: 12,
    valid_rows: 12,
    error_rows: 0,
    errors: [],
    errors_truncated: false,
    created_at: '2026-07-17T00:00:00Z',
    validated_at: ['READY', 'COMMITTING', 'COMMITTED'].includes(status) ? '2026-07-17T00:00:01Z' : null,
    committed_at: status === 'COMMITTED' ? '2026-07-17T00:00:02Z' : null,
  };
}

describe('ImportProductsModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.previewImport.mockResolvedValue(importJob('UPLOADED'));
    let poll = 0;
    mocks.importJob.mockImplementation(async () => {
      poll += 1;
      if (poll === 1) return importJob('VALIDATING');
      if (poll === 2) return importJob('READY');
      return importJob('COMMITTED');
    });
    mocks.commitImport.mockResolvedValue(importJob('COMMITTING'));
  });

  it('polls asynchronous preflight and commit jobs through completion', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const onCommitted = vi.fn();
    const view = render(
      <AntApp>
        <QueryClientProvider client={client}>
          <ImportProductsModal open onClose={vi.fn()} onCommitted={onCommitted} />
        </QueryClientProvider>
      </AntApp>,
    );

    const fileInput = document.querySelector('.ant-modal input[type="file"]');
    if (!(fileInput instanceof HTMLInputElement)) {
      throw new Error('product import file input was not rendered');
    }
    fireEvent.change(fileInput, { target: { files: [new File(['mock workbook'], 'products.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })] } });
    await waitFor(() => expect(screen.getByText('products.xlsx')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '开始预检' }));

    expect(await screen.findByText('预检通过，可整体提交', {}, { timeout: 3000 })).toBeInTheDocument();
    expect(mocks.importJob).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '整体提交' }));

    await waitFor(() => expect(onCommitted).toHaveBeenCalledTimes(1), { timeout: 3000 });
    expect(mocks.commitImport).toHaveBeenCalledWith('0d15e762-38f9-4b9e-ab72-e4c40482816d');

    view.unmount();
  });
});
