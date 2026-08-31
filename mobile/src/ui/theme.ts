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
  background: '#F3F7FC',
  surface: '#FFFFFF',
  surfaceMuted: '#EAF1F8',
  text: '#10233F',
  secondary: '#61748A',
  primary: '#2878D0',
  primarySurface: '#E1EFFD',
  hero: '#163A64',
  heroMuted: '#BFDDF8',
  border: '#D8E3EF',
  disabled: '#A8B6C6',
  danger: '#C53D4D',
  dangerSurface: '#FCE7EA',
  success: '#238265',
  successSurface: '#DDF4EB',
  amber: '#FFB547',
  shadow: '#163A64',
};

export const darkColors: AppColors = {
  background: '#081321',
  surface: '#111F31',
  surfaceMuted: '#172A40',
  text: '#F3F7FC',
  secondary: '#AABBD0',
  primary: '#63B3FF',
  primarySurface: '#163A5B',
  hero: '#164A78',
  heroMuted: '#C8E5FF',
  border: '#294059',
  disabled: '#53657A',
  danger: '#FF8792',
  dangerSurface: '#3B1C27',
  success: '#62D0A8',
  successSurface: '#173C36',
  amber: '#FFC766',
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
