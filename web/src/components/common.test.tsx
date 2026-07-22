import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusBadge } from './common';

describe('StatusBadge', () => {
  it('renders a text label and a semantic marker', () => {
    const { container } = render(<StatusBadge status="PARTIALLY_CONFIRMED" />);
    expect(screen.getByText('部分确认')).toBeInTheDocument();
    expect(container.querySelector('.status-dot')).toBeInTheDocument();
    expect(container.querySelector('.status-warning')).toBeInTheDocument();
  });

  it('allows an explicit operational label without losing tone', () => {
    const { container } = render(<StatusBadge status="OFFLINE" label="Broker 离线" />);
    expect(screen.getByText('Broker 离线')).toBeInTheDocument();
    expect(container.querySelector('.status-error')).toBeInTheDocument();
  });
});
