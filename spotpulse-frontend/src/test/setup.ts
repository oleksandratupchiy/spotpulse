import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach } from 'vitest';
// Match the existing Auth/Map allowance for first imports on slower Windows workers.
configure({ asyncUtilTimeout: 10_000 });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
