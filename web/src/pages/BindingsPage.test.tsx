import { App as AntApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import BindingsPage from './BindingsPage';

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  create: vi.fn(),
  remove: vi.fn(),
  rebind: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  api: {
    bindings: {
      list: mocks.list,
      create: mocks.create,
      remove: mocks.remove,
      rebind: mocks.rebind,
    },
  },
}));

describe('BindingsPage batch binding', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.list.mockResolvedValue({ items: [], pagination: { page: 1, page_size: 20, total_items: 0, total_pages: 0 } });
    mocks.create.mockImplementation(async ({ tag_id }: { tag_id: string }) => {
      if (tag_id === 'AD1000000002') throw new ApiError('灯条已绑定其他产品', 409, 'TAG_ALREADY_BOUND');
      return { id: 'binding-created', tag_id };
    });
  });

  it('keeps only failed tags in the form after a partial batch result', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const view = render(<AntApp><QueryClientProvider client={client}><BindingsPage /></QueryClientProvider></AntApp>);

    await screen.findByText('暂无有效绑定');
    fireEvent.click(screen.getByRole('button', { name: '新建绑定' }));
    fireEvent.change(screen.getByLabelText('产品编码'), { target: { value: 'HT-E2E-001' } });
    fireEvent.change(screen.getByLabelText('灯条 ID（可输入多个）'), { target: { value: 'AD1000000001\nAD1000000002' } });
    fireEvent.change(screen.getByLabelText('基站 SN'), { target: { value: '90A9F7301427' } });
    fireEvent.click(screen.getByRole('button', { name: '创建绑定' }));

    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByLabelText('灯条 ID（可输入多个）')).toHaveValue('AD1000000002'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    view.unmount();
  });
});
