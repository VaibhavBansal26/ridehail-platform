/// <reference types="jest" />
// the OS modules have no JavaScript implementation in a test run; each test that cares sets its own behaviour
jest.mock("expo-secure-store", () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));
jest.mock("expo-task-manager", () => ({ defineTask: jest.fn() }));
jest.mock("expo-location", () => ({
  Accuracy: { High: 5 },
  getForegroundPermissionsAsync: jest.fn(),
  requestForegroundPermissionsAsync: jest.fn(),
  requestBackgroundPermissionsAsync: jest.fn(),
  hasStartedLocationUpdatesAsync: jest.fn(),
  startLocationUpdatesAsync: jest.fn(),
  stopLocationUpdatesAsync: jest.fn(),
  watchPositionAsync: jest.fn(),
  getCurrentPositionAsync: jest.fn(),
}));

// tell React these tests run under act(), so a query timer firing after a test ends does not warn or corrupt the next one
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

jest.mock("expo-notifications", () => ({
  setNotificationHandler: jest.fn(),
  setNotificationChannelAsync: jest.fn(),
  getPermissionsAsync: jest.fn(),
  requestPermissionsAsync: jest.fn(),
  getExpoPushTokenAsync: jest.fn(),
  useLastNotificationResponse: jest.fn(() => null),
  AndroidImportance: { HIGH: 4 },
  IosAuthorizationStatus: { PROVISIONAL: 3 },
  DEFAULT_ACTION_IDENTIFIER: "expo.modules.notifications.actions.DEFAULT",
}));
jest.mock("expo-constants", () => ({ __esModule: true, default: { expoConfig: { extra: { eas: { projectId: "test-project" } } } } }));

jest.mock("expo-image-picker", () => ({
  requestCameraPermissionsAsync: jest.fn(),
  launchCameraAsync: jest.fn(),
  launchImageLibraryAsync: jest.fn(),
  CameraType: { front: "front", back: "back" },
}));
