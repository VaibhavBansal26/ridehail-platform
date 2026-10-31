import * as Location from "expo-location";
import * as TaskManager from "expo-task-manager";
import { createBackgroundHandler, LOCATION_TASK, startBackgroundTracking, stopBackgroundTracking } from "@/lib/background";

jest.mock("@/lib/api", () => ({ api: { rider: { updateLocation: jest.fn() } } }));

const at = (latitude: number, longitude: number, heading: number | null = null) => ({ coords: { latitude, longitude, heading } });
const loc = Location as jest.Mocked<typeof Location>;

describe("the background handler", () => {
  function setup(foreground = false) {
    let t = 0;
    const send = jest.fn().mockResolvedValue(undefined);
    const handle = createBackgroundHandler({ send, isForeground: () => foreground, now: () => t });
    return { send, handle, at: (ms: number) => void (t = ms) };
  }

  it("sends only the latest position of a batch", async () => {
    const s = setup();
    await s.handle([at(1, 1), at(2, 2), at(3, 3, 90)]);
    expect(s.send).toHaveBeenCalledTimes(1);
    expect(s.send).toHaveBeenCalledWith({ lat: 3, lon: 3, heading: 90 });
  });

  it("leaves out a heading the phone does not know (null or -1)", async () => {
    const s = setup();
    await s.handle([at(1, 1, -1)]);
    await s.handle([at(2, 2, null)]);
    expect(s.send.mock.calls[0]![0]).toEqual({ lat: 1, lon: 1 });
  });

  it("does nothing while the app is open, because the foreground reporter is already sending", async () => {
    const s = setup(true);
    await s.handle([at(1, 1)]);
    expect(s.send).not.toHaveBeenCalled();
  });

  it("keeps to the server's pace, and ignores an empty batch", async () => {
    const s = setup();
    await s.handle([]);
    await s.handle([at(1, 1)]);
    s.at(1000);
    await s.handle([at(2, 2)]);
    s.at(4000);
    await s.handle([at(3, 3)]);
    expect(s.send.mock.calls.map((c) => c[0].lat)).toEqual([1, 3]);
  });

  it("backs off for as long as a 429 says, and drops other failures without throwing", async () => {
    const s = setup();
    s.send.mockRejectedValueOnce({ isRateLimited: true, retryAfterSeconds: 30 });
    await s.handle([at(1, 1)]);
    s.at(10_000);
    await s.handle([at(2, 2)]);
    expect(s.send).toHaveBeenCalledTimes(1);
    s.at(30_000);
    s.send.mockRejectedValueOnce(new Error("offline"));
    await expect(s.handle([at(3, 3)])).resolves.toBeUndefined();
    expect(s.send).toHaveBeenCalledTimes(2);
  });
});

describe("the task and its controls", () => {
  beforeEach(() => jest.clearAllMocks());

  it("is defined when the module loads, so the OS can run it headless", () => {
    jest.isolateModules(() => {
      require("@/lib/background");
      expect(jest.requireMock("expo-task-manager").defineTask).toHaveBeenCalledWith(LOCATION_TASK, expect.any(Function));
    });
  });

  it("starts with a foreground-service notification once 'Always' is allowed", async () => {
    loc.getForegroundPermissionsAsync.mockResolvedValue({ status: "granted" } as never);
    loc.requestBackgroundPermissionsAsync.mockResolvedValue({ status: "granted" } as never);
    loc.hasStartedLocationUpdatesAsync.mockResolvedValue(false);
    expect(await startBackgroundTracking()).toBe(true);
    expect(loc.startLocationUpdatesAsync).toHaveBeenCalledWith(LOCATION_TASK, expect.objectContaining({ foregroundService: expect.objectContaining({ notificationTitle: expect.any(String) }) }));
  });

  it("does not start twice", async () => {
    loc.getForegroundPermissionsAsync.mockResolvedValue({ status: "granted" } as never);
    loc.requestBackgroundPermissionsAsync.mockResolvedValue({ status: "granted" } as never);
    loc.hasStartedLocationUpdatesAsync.mockResolvedValue(true);
    expect(await startBackgroundTracking()).toBe(true);
    expect(loc.startLocationUpdatesAsync).not.toHaveBeenCalled();
  });

  it("reports false, quietly, when 'Always' is refused, the foreground permission is missing, or the platform cannot do it", async () => {
    loc.getForegroundPermissionsAsync.mockResolvedValue({ status: "denied" } as never);
    expect(await startBackgroundTracking()).toBe(false);
    loc.getForegroundPermissionsAsync.mockResolvedValue({ status: "granted" } as never);
    loc.requestBackgroundPermissionsAsync.mockResolvedValue({ status: "denied" } as never);
    expect(await startBackgroundTracking()).toBe(false);
    loc.requestBackgroundPermissionsAsync.mockResolvedValue({ status: "granted" } as never);
    loc.hasStartedLocationUpdatesAsync.mockRejectedValue(new Error("not supported in Expo Go"));
    expect(await startBackgroundTracking()).toBe(false);
    expect(loc.startLocationUpdatesAsync).not.toHaveBeenCalled();
  });

  it("stops only what is running", async () => {
    loc.hasStartedLocationUpdatesAsync.mockResolvedValue(false);
    await stopBackgroundTracking();
    expect(loc.stopLocationUpdatesAsync).not.toHaveBeenCalled();
    loc.hasStartedLocationUpdatesAsync.mockResolvedValue(true);
    await stopBackgroundTracking();
    expect(loc.stopLocationUpdatesAsync).toHaveBeenCalledWith(LOCATION_TASK);
  });
});

void TaskManager;
