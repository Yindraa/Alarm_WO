export type AppColors = Readonly<{
  background: string;
  surface: string;
  surfaceMuted: string;
  text: string;
  secondary: string;
  primary: string;
  primarySurface: string;
  hero: string;
  heroMuted: string;
  border: string;
  disabled: string;
  danger: string;
  dangerSurface: string;
  success: string;
  successSurface: string;
  amber: string;
  shadow: string;
}>;

export const lightColors: AppColors = {
  background: '#F4F0E8',
  surface: '#FFFCF6',
  surfaceMuted: '#EAE4D9',
  text: '#1C2926',
  secondary: '#68736D',
  primary: '#A94832',
  primarySurface: '#F1DDD4',
  hero: '#172B33',
  heroMuted: '#C1D0D0',
  border: '#D8D0C3',
  disabled: '#A8AAA3',
  danger: '#A33D47',
  dangerSurface: '#F4DFE1',
  success: '#34715C',
  successSurface: '#DDE9E2',
  amber: '#E9A33F',
  shadow: '#172B33',
};

export const darkColors: AppColors = {
  background: '#101714',
  surface: '#18221E',
  surfaceMuted: '#222E29',
  text: '#F2EEE5',
  secondary: '#ACB5AE',
  primary: '#F08A68',
  primarySurface: '#4A2921',
  hero: '#10272F',
  heroMuted: '#C5D4D2',
  border: '#35433C',
  disabled: '#657069',
  danger: '#FF9299',
  dangerSurface: '#48252B',
  success: '#78C5A5',
  successSurface: '#1E4034',
  amber: '#F1B65C',
  shadow: '#000000',
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

export const radius = {
  sm: 12,
  md: 18,
  lg: 24,
  pill: 999,
} as const;
