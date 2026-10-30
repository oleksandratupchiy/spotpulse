import { render, screen } from '@testing-library/react';
import { SpaceImage } from '../components';

test.each([
  '/api/spaces/images/11111111-1111-4111-8111-111111111111.png',
  'https://images.example.test/legacy.jpg',
  'https://res.cloudinary.com/demo/image/upload/v1770000000/spotpulse/spaces/11111111-1111-4111-8111-111111111111.png',
])('renders mixed-provider image directly: %s', url => {
  render(<SpaceImage url={url} name="Coworking Hub"/>);
  const image = screen.getByRole('img', { name: 'Coworking Hub' });
  expect(image).toHaveAttribute('src', url);
  expect(image).toHaveAttribute('loading', 'lazy');
  expect(image).not.toHaveAttribute('crossorigin');
});
