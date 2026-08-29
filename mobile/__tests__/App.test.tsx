import {render} from '@testing-library/react-native';
import App from '../App';
import {getContractInfo} from '../src/native/missionAlarm';

jest.mock('../src/native/missionAlarm', () => ({
  getContractInfo: jest.fn(),
}));

const getContractInfoMock = jest.mocked(getContractInfo);

describe('application bootstrap', () => {
  beforeEach(() => {
    getContractInfoMock.mockReset();
  });

  it('renders the verified native contract', async () => {
    getContractInfoMock.mockResolvedValue({
      contractVersion: 1,
      minimumClientContractVersion: 1,
      moduleName: 'NativeMissionAlarm',
      nativeBuildVersion: '1.0',
    });

    const view = await render(<App />);

    expect(await view.findByText('Fondasi aplikasi siap')).toBeOnTheScreen();
    expect(view.getByText('Kontrak native v1 · build 1.0')).toBeOnTheScreen();
  });

  it('fails closed when the native contract cannot be verified', async () => {
    getContractInfoMock.mockRejectedValue(new Error('NATIVE_UNAVAILABLE'));

    const view = await render(<App />);

    expect(
      await view.findByText('Fondasi native belum tersedia'),
    ).toBeOnTheScreen();
    expect(view.queryByText('Fondasi aplikasi siap')).not.toBeOnTheScreen();
  });
});
